#!/bin/bash
if [ ! -f "config.properties" ]; then
    echo "ERROR: config.properties not found!"
    echo "Copy config.properties.example to config.properties and fill in your credentials."
    exit 1
fi
mvn compile -q exec:java
