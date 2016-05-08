#!/usr/bin/env perl

use strict;
use warnings;
use utf8;
use 5.016;
use autodie qw(:file);
use open qw(:utf8 :std);

use LWP::UserAgent;
use MCE::Loop;
use POSIX qw(ceil);
use RDF::Trine;
use RDF::Trine::Store::Redis;
use URI::Escape qw(uri_escape);
use XML::XPath;
use YAML::XS qw(LoadFile);

=encoding UTF-8

=head1 SOCH-Fetch

I<A script for scraping RDF linked data from the Swedish Open Cultural Heritage index and its UGC hub, via their web-APIs.>

The SOCH API is pretty great, but if you want to run SPARQL queries on SOCH data – especially federated queries! – it can be useful to have a local copy of the triples to play with.

For each organisation/service, SOCH objects are fetched and parsed in parallel, then cached. The caches are then serialised to Turtle and written to disk (this part is very slow).

To use this script, you will need API keys for SOCH and its UGC hub, and a Redis server to cache the triples prior to serialisation.

(It turns out that Redis does a really fast approximation of a triplestore for certain types of applications!)

=cut

###

# RDF::Trine's XML parser silently resolves entities, including '&amp;', rendering the XML malformed; we don't want parser errors filling up the terminal:
open(STDERR, '>', 'error.log');

# Enforce proper garbage collection on XML::XPath:
$XML::XPath::SafeMode = 1;

{
	# Read in config file:
	my %conf = %{LoadFile('./config.yml')} or die 'Failed to read config file: ', $!, "\n";
	
	# Create a user agent object:
	my $ua = LWP::UserAgent->new();
	$ua->agent($conf{'user-agent'});
	$ua->default_header('Accept' => 'application/rdf+xml, application/xml, text/xml');

	# Create a connection to Redis to store our RDF for the duration of the run:
	my $store = RDF::Trine::Store::Redis->new(server => $conf{redis}{server},
	                                          on_connect => sub {shift()->select($conf{redis}{triples})},
	                                         );
	my $model = RDF::Trine::Model->new($store);

	# Define namespaces:
	my $prefixes = RDF::Trine::NamespaceMap->new($conf{prefixes});

	# Set up parsers and serialisers:
	my $xml_parser        = RDF::Trine::Parser->new('rdfxml');
	my $turtle_serialiser = RDF::Trine::Serializer->new('turtle', namespaces => $prefixes);

	# Use MCE when fetching RDF:
	MCE::Loop::init {
		max_workers => 'auto',
		chunk_size  => 'auto',
	};

	say 'Fetching list of providers…';
	for my $provider (get_service_orgs(\%conf, $ua)) {
#		next unless $provider eq 'raä'; # TESTING
		say 'Fetching list of services provided by ', $provider, '…';
		for my $service (get_services(\%conf, $ua, $provider)) {
#			next unless $service eq 'fmi'; # TESTING
			say "\t", 'Fetching URIs and RDF for service ', $service, '…';
			$model->begin_bulk_ops(); # Treat all of this as a single transaction

			# Main MCE (parallel) loop for fetching, parsing, and cacheing RDF/XML:
			mce_loop {
				for my $uri (@{$_}) {
					get_rdf(\%conf, $ua, $xml_parser, $prefixes, $model, $uri);
				}
			} @{list_uris(\%conf, $ua, $provider, $service)};

			$model->end_bulk_ops();
			say "\t", join(' ', $model->size, 'triples cached for provider', $provider . ',', 'service', $service . '!');

			# Dump out the whole graph (slow!):
			say "\t", 'Serialising ', $service, ' triples as Turtle (this might take a while!)…';
			open (my $fh, '>', join('', './cache/', join(' - ', $provider, $service), '.ttl'));
			$turtle_serialiser->serialize_model_to_file($fh, $model);
			close $fh;

			# Clear the graph before fetching the next batch:
			$store->nuke();
		}
	}
	# Clean up:
	MCE::Loop::finish;
	say '…done!';
}
# Exeunt omnes, laughing.

###

# Query SOCH for a list of all service organisations providing data:
sub get_service_orgs {
	my %conf = %{shift()};
	my $ua = shift;
	my $req = HTTP::Request->new(GET => join('', 'http://kulturarvsdata.se/ksamsok/api?x-api=', $conf{'api keys'}{soch}, '&method=statistic', '&index=', 'serviceOrganization=*'));
	return(statistic_values($ua, $req));
}


# Query SOCH for a list of all services from the service organisation (provider) in question:
sub get_services {
	my %conf = %{shift()};
	my ($ua, $provider) = @_;
	my $req = HTTP::Request->new(GET => join('', 'http://kulturarvsdata.se/ksamsok/api?x-api=', $conf{'api keys'}{soch}, '&method=statisticSearch', '&index=', 'serviceName=*', '&query=serviceOrganization="', $provider, '"&removeBelow=1'));
	return(statistic_values($ua, $req));
}


# Helper function to fetch and extract text from <value> nodes in statistic and statisticSearch queries:
sub statistic_values {
	my ($ua, $req) = @_;
	$req->accept_decodable;
	my $response = $ua->request($req);
	unless ($response->is_success) {
		die 'Error fetching providers/services: ', $response->status_line, "\n";
	}
	my $xp = XML::XPath->new($response->decoded_content);
	my $values = $xp->find('/result/term/indexFields/value/text()');
	my @values = map {XML::XPath::XMLParser::as_string($_)} $values->get_nodelist();
	$xp->cleanup();
	return @values;
}


# Return a list of all URIs for a particular service:
sub list_uris {
	my %conf = %{shift()};
	my ($ua, $provider, $service) = @_;
	my @uris;

	# Search results are paged; how many pages will we need to request?
	my $pages = ceil(get_hits(\%conf, $ua, $provider, $service)/$conf{'page size'});

	# Fetch all results pages in parallel, and append their object URIs to the list:
	my $mce = MCE->new(
	                   max_workers => 'auto',
	                   chunk_size  => ceil($pages/8),
	                   gather      => sub {
		                   push @uris, @_;
	                   },
	                   user_func   => sub {
		                   my ($mce, $chunk_ref, $chunk_id) = @_;
		                   for my $page (@{$chunk_ref}) {
			                   MCE->gather(@{get_uris(\%conf, $ua, $page, $provider, $service)});
		                   }
	                   },
	                  );

	$mce->process([1..$pages]);
	$mce->shutdown;
	return \@uris;
}


# Query SOCH for the number of objects provided by a particular service:
sub get_hits {
	my %conf = %{shift()};
	my ($ua, $provider, $service) = @_;
	my $req = HTTP::Request->new(GET => join('', 'http://kulturarvsdata.se/ksamsok/api?x-api=', $conf{'api keys'}{soch}, '&method=search', '&hitsPerPage=', 1, '&startRecord=', 1, '&query=serviceOrganization="', $provider, '" and serviceName="', $service, '"', '&sort=itemName'));
	$req->accept_decodable;
	my $response = $ua->request($req);
	unless ($response->is_success) {
		warn 'Error fetching number of hits: ', $response->status_line, "\n";
		return 0;
	}
	my $xp = XML::XPath->new($response->decoded_content);
	my $nodes = $xp->find('/result/totalHits/text()');
	my ($hits) = map {XML::XPath::XMLParser::as_string($_)} $nodes->get_nodelist();
	$xp->cleanup();
	return $hits;
}


# Query SOCH for a list of URIs for all objects provided by a particular service from a particular page of results:
sub get_uris {
	my %conf = %{shift()};
	my ($ua, $page, $provider, $service) = @_;
	my %uris;
	my $start_record = (($page-1)*$conf{'page size'})+1;
	my $req = HTTP::Request->new(GET => join('', 'http://kulturarvsdata.se/ksamsok/api?x-api=', $conf{'api keys'}{soch}, '&method=search', '&hitsPerPage=', $conf{'page size'}, '&startRecord=', $start_record, '&query=serviceOrganization="', $provider, '" and serviceName="', $service, '"', '&sort=itemName'));
	$req->accept_decodable;
	my $response = $ua->request($req);
	unless ($response->is_success) {
		warn 'Error fetching URI search results: ', $response->status_line, "\n";
		return [];
	}
	my $xp = XML::XPath->new($response->decoded_content);
	my $nodes = $xp->find('/result/records/record/rdf:RDF/rdf:Description/@rdf:about | /result/records/record/rdf:RDF/Entity/@rdf:about');
	map {$uris{$_->getNodeValue()} = 1} $nodes->get_nodelist();
	$xp->cleanup();
	return [keys %uris];
}


# Dereference the URIs we have, get their RDF, parse it, and add it to the cache:
sub get_rdf {
	my %conf = %{shift()};
	my ($ua, $xml_parser, $prefixes, $model, $uri) = @_;

	# For SOCH objects, fetch, and filter out XML presentation data, which is bullshit:
	my $req = HTTP::Request->new(GET => $uri);
	$req->accept_decodable;
	my $response = $ua->request($req);
	unless ($response->is_success) {
		warn join('', 'Error dereferencing URI <', $uri, '>. : ', $response->status_line, "\n");
		return;
	}
	my $rdf = $response->decoded_content;
	$xml_parser->parse($uri, $rdf,  sub {
		                   my $triple = shift;
		                   # Filter out presentation XML:
		                   return if ($triple->predicate()->equal($prefixes->soch('presentation')));
		                   # Fix Libris URLs:
		                   if ($triple->object()->as_string() =~ m|^[<"]?http://libris\.kb\.se/|) {
			                   $triple = RDF::Trine::Statement->new(
			                                                        $triple->subject(),
			                                                        $triple->predicate(),
			                                                        RDF::Trine::Node::Resource->new(fix_libris($triple->object()->as_string()))
			                                                       );
		                   }
		                   # Fix CIDOC-CRM URIs:
		                   if ($triple->predicate()->as_string() =~ m|^<?http://www\.cidoc-crm\.org/rdfs/cidoc-crm#| ||
		                       $triple->object()->as_string()    =~ m|^<?http://www\.cidoc-crm\.org/rdfs/cidoc-crm#|) {
			                   $triple = RDF::Trine::Statement->new(
			                                                        $triple->subject(),
			                                                        RDF::Trine::Node::Resource->new(fix_cidoc($triple->predicate()->as_string())),
			                                                        RDF::Trine::Node::Resource->new(fix_cidoc($triple->object()->as_string()))
			                                                       );
		                   }
		                   # Insert statement into the cache:
		                   $model->add_statement($triple);
		                   return;
	                   });

	# For UGC objects, check they exist, then construct the triple by hand because the UGC hub doesn't provide RDF and uses made-up predicates:
	$req = HTTP::Request->new(GET => join('', 'http://ugc.kulturarvsdata.se/UGC-hub/api?x-api=ex2147ap36&method=retrieve&scope=all&objectUri=', uri_escape($uri)));
	$req->accept_decodable;
	$response = $ua->request($req);
	if ($response->is_success) {
		my $xp = XML::XPath->new($response->decoded_content);
		my $relations = $xp->find('/response/relations/relation');
		for my $relation ($relations->get_nodelist()) {
			my $subject = RDF::Trine::Node::Resource->new($relation->find('./uri/text()'));
			my $ugc_predicate = $relation->find('./relationType/text()');
			my $predicate = ((exists $conf{'ugc predicates'}{$ugc_predicate}) ? RDF::Trine::Node::Resource->new($conf{'ugc predicates'}{$ugc_predicate}) : $prefixes->soch($ugc_predicate));
			my $ugc_object = $relation->find('./relatedUri/text()');

			# Fix Libris URLs:
			$ugc_object = fix_libris($ugc_object) if ($ugc_object =~ m|^[<"]?http://libris\.kb\.se/|);
			my $object = RDF::Trine::Node::Resource->new($ugc_object);
			my $triple = RDF::Trine::Statement->new($subject, $predicate, $object);
			$model->add_statement($triple);
		}
		$xp->cleanup();
	}
	return;
}


# Fix Libris URLs to be the correct URIs:
sub fix_libris {
	my $uri = shift;
	for ($uri) {
		s|^[<"]||;
		s|[>"]$||;
		s!^http://libris\.kb\.se/(bib|auth)/!http://libris\.kb\.se/resource/$1/!;
	}
	return $uri;
}


# Fix CIDOC-CRM URIs for some providers who use a non-canonical namespace:
sub fix_cidoc {
	my $uri = shift;
	for ($uri) {
		s|^<||;
		s|>$||;
		s|^http://www\.cidoc-crm\.org/rdfs/cidoc-crm#|http://www.cidoc-crm.org/cidoc-crm/|;
		s|([A-Z][0-9]{1,3})F\.|$1_|; # Fix fragments
		s|([A-Z][0-9]{1,3})B\.|$1i_|; # Fix inverse fragments
	}
	return $uri;
}
