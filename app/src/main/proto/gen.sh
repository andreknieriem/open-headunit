#!/bin/bash

# Change to the directory where .proto files are located
cd app/src/main/proto/

/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc common.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc control.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc input.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc media.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc navigation.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc playback.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc sensors.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc wireless.proto --java_out=../java/
/Users/andre/Downloads/protoc-25.1-osx-aarch_64/bin/protoc bluetooth.proto --java_out=../java/

# Go back to the original directory
cd -