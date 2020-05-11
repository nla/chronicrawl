#!/bin/bash
mvn package
cp target/*-with-dependencies.jar $1/outbackcdx.jar