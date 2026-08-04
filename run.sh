#!/bin/bash

# Check if a path argument was provided
if [ -z "$1" ]; then
    echo "usage: ./run.sh <file>"
    exit 1
fi

java -jar build/libs/alpaca.jar "$1"