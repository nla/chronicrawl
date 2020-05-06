# Chronicrawl [<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Pocketwatch_cutaway_drawing.jpg/640px-Pocketwatch_cutaway_drawing.jpg" align="right" width="300px">](https://en.wikipedia.org/wiki/File:Pocketwatch_cutaway_drawing.jpg)

Chronicrawl is an experimental web crawler for web archiving. The goal is to explore some ideas around budget-based
continuous crawling and mixing of browser-based crawling with traditional link extraction.

## Status

Chronicrawl is in an early stage of development and is barely usable. Currently it can:

* keep the crawl state in an embedded H2 SQL database
* fetch robots.txt and discover URLs via sitemaps
* discover subresources by replaying captured documents to headless Chromium
* periodically revisit resources (schedule currently hardcoded)
* write WARC records (with both server not modified and identical digest dedupe)
* show a primitive UI for exploring the state of the crawl and testing the browser integration

but many serious limitations still need to be addressed:

* the main crawl loop is single-threaded
* error handling is incomplete
* there's no balancing of crawls against different sites
* the revisit schedule is hardcoded
* no effort has been put into performance yet, the database doesn't even have indexes
* it only speaks HTTP/1.0 without keep-alive
* link extraction is not yet implemented
* essential options like url scoping are missing

## Requirements

* Java 11 or later
* Chromium or Chrome (currently mandatory, may be optional in future)
* Pywb (optional)

## Usage

To compile install Apache Maven and then run:

    mvn package
    java -jar target/chronicrawl-*-with-dependencies.jar
    
The first time you run Chronicrawl pass it the --init option to create the database. (This option will currently 
also wipe an existing database.)

## Configuration

See [Config.java](src/org/netpreserve/chronicrawl/Config.java) for the full list of configuration options. They can be
set as environment variables, system properties or read from a properties file using the `-c` option.

### Pywb integration

Chronicrawl can optionally run an instance of Pywb for replay. To enable this specify the path to the pywb main
executable:

    PYWB=/usr/bin/pywb PYWB_PORT=8081 java -jar ... 

## License

Copyright 2020 National Library of Australia and contributors

Licensed under the Apache License, Version 2.0