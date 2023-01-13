# Chronicrawl [<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Pocketwatch_cutaway_drawing.jpg/640px-Pocketwatch_cutaway_drawing.jpg" align="right" width="300px">](https://en.wikipedia.org/wiki/File:Pocketwatch_cutaway_drawing.jpg)

Chronicrawl is an experimental web crawler for web archiving. The goal is to explore some ideas around budget-based
continuous crawling and mixing of browser-based crawling with traditional link extraction.

## Status

**Current unmaintained. May or may not be revisited in future.** Chronicrawl is very rough around the edges but (barely) usable for basic crawling functions. It's likely not compatible with the latest version of Chromium.

<img height=128px src="https://user-images.githubusercontent.com/10588/82002093-d79f7080-9697-11ea-9ae7-8049e54e2dae.png"> <img height=128px src="https://user-images.githubusercontent.com/10588/82002097-da9a6100-9697-11ea-954d-57590df1abe2.png"> <img height=128px src="https://user-images.githubusercontent.com/10588/82001920-4defa300-9697-11ea-9ef0-d1a11b3a815f.png">

Currently it:

* keeps the crawl state in an embedded ~~H2~~ Sqlite SQL database (still experimenting with db options, it currently
  uses a fairly portable subset of SQL and likely will target both an embedded and an clustered database)
* fetches robots.txt and discover URLs via sitemaps and links
* discovers subresources by parsing HTML and also loading in Headless Chromium when script tags are detected
* periodically revisits resources (both fine-grained manual and basic automatic scheduling using a content change heuristic)
* writes WARC records (with both server not modified and identical digest dedupe)
* shows a primitive UI for exploring the state of the crawl and examining the content analysis
* replays archived content using Pywb

but many serious limitations still need to be addressed:

* the main crawl loop is single-threaded
* error handling is incomplete
* there's no real prioritisation system yet
* only a little effort has been put into performance so far
* it only speaks HTTP/1.0 without keep-alive
* essential options like url scoping are missing

## Requirements

* Java 11 or later
* Chromium or Chrome (currently mandatory, may be optional in future)
* Pywb (optional)

## Usage

To compile install Apache Maven and then run:

    mvn package
    java -jar target/chronicrawl-*-with-dependencies.jar

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
