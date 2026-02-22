#!/bin/sh
# Exit script on errors unless handled explicitly
set -e

echo "CMD START"
echo "Docker CMD: $@"

if "$@"; then
    exit_code=0
    echo "CMD FINISH"
else
    exit_code=$?
    echo "CMD FINISH with error (exit code: $exit_code)"
fi

exit $exit_code
