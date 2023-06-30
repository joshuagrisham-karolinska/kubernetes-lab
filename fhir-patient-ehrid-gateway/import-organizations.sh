#!/bin/sh

# Currently the data in the input file needs to be sorted in dependency order (e.g. parents need to come before children)
# TODO: maybe this script could just re-sort the data somehow? Maybe a nice feature exists in jq or something?

baseurl="$1"
filename="$2"
while read -r line; do
    id=`echo $line | jq -r '.id'`
    echo Organization ID: $id
    echo " => PUTting organization to \"${baseurl}/Organization/${id}\""
    curl --verbose --header "Content-Type: application/json" --data "$line" --request PUT "${baseurl}/Organization/${id}"
    echo
    echo
done < "$filename"
