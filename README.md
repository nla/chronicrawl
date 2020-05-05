# Chronicrawl [<img src="https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Pocketwatch_cutaway_drawing.jpg/640px-Pocketwatch_cutaway_drawing.jpg" align="right" width="300px">](https://en.wikipedia.org/wiki/File:Pocketwatch_cutaway_drawing.jpg)

Chronicrawl is an experimental continuous web crawler for web archiving.

## Status

Currently:

* keeps the crawl state in an embedded H2 SQL database
* fetches robots.txt and discovers URLs via sitemaps
* discovers subresources by replaying archived html to headless Chromium
* has a simple hard-coded revisit schedule
* writes WARC records including revisits (both the identical digest and server not modified profiles)

Still to do:

* classical link extraction to discover pages and subresources that aren't automatically loaded
* page interaction behaviours (maybe reuse Brozzler's?)
* integration with an external CDX server
* performance optimisations (currently there aren't even any database indexes)
* more scalable database options
* UI for the operator to fine tune priorities and storage budget

Currently not planning to do:

* extensive configuration flexibility or plugin system
* multiple crawl jobs
* one-shot crawls

## Requirements

* Java 11 or later
* Chromium or Chrome

## License

Copyright 2020 National Library of Australia and contributors

Licensed under the Apache License, Version 2.0
