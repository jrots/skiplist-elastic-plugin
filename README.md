# skiplist-elastic-plugin

Plugin for elastic (6.2) to skip large lists of numeric integers, _ids or ids on another field. (read more: then 1000 or 100000 ids) 

I make use of roaring bitmaps to pass these ids to elastic, 
http://roaringbitmap.org/, https://github.com/RoaringBitmap/RoaringBitmap

The current "term" logic is kind of broken imho and can't scale effectively beyond 64K "terms/ ids".
This plugin is 10 times faster when doing a simple search on a set of 30K documents with a skiplist that contains 100K ids. 

The data field needs to contain a base64 encode representation of the skiplist, 

## Sample call : 
```
....
curl -XGET 'localhost:9200/_search?pretty' -H 'Content-Type: application/json' -d'
{
    "query": {
        "function_score": {
	    "min_score" : 0,
            "query": {
               "match_all": {}
            },
            "script_score" : {
                "script" : {
                  "lang" : "skiplist",
                  "source" : "roaring",
                  "params" : {
                      "field" : "_id",
                      "data" : "OjAAAAEAAAAAAAYAEAAAAAEAAgADAAQABQBkAOgD"
                  }
                }
            }
        }
    }
}
'
```

--- 

## PHP example 
usage of this in PHP / using "Go" as a middle layer, to create the roaring bitmap, as binding aren't supported for PHP yet; 
I use this PHP module to create the roaring bitmap:
using : https://github.com/arnaud-lb/php-go
```
package main

import (
    "github.com/arnaud-lb/php-go/php-go"
    "github.com/RoaringBitmap/roaring"
    "strconv"
    "strings"
     b64 "encoding/base64"	
    "bytes"
)

var _ = php.Export("roaring", map[string]interface{}{
    "compressIntArray": CompressIntArray,
})

func main() {
	// test code for roaring
	//fmt.Println("==roaring==")
	//fmt.Println(Compress("1 2 3 4"))
	//fmt.Println(Compress("1 2 3 10000 50000 70000"))
}

func CompressIntArray(data string) string {
    a := StringToArray(data)
    rb := roaring.BitmapOf(a...)
    buf := &bytes.Buffer{}
    _, err := rb.WriteTo(buf)
    if err != nil {
		return ""
    }
    return b64.StdEncoding.EncodeToString(buf.Bytes())
}

func StringToArray(A string) []uint32 {
    a := strings.Split(A, " ")
    b := make([]uint32, len(a))
    for i, v := range a {
        i64, _ := strconv.ParseInt(v, 10, 32) 
        b[i] = uint32(i64)
    }
    return b
}

```

PHP create roaring bitmap : 
```
$roaring = phpgo_load('roaring.so', "roaring");

$ints = [];
for ($i=0;$i<100000;$i++)
{
	$ints[] = mt_rand(0,1000000);
}
//echo implode(",", $ints);

$data =  $roaring->compress(implode(array_unique($ints), " "));

// Data will be something like: OjAAAAEAAAAAAAYAEAAAAAEAAgADAAQABQBkAOgD

```

PHP call to elastic: 

```
....
curl -XGET 'localhost:9200/_search?pretty' -H 'Content-Type: application/json' -d'
{
    "query": {
        "function_score": {
	    "min_score" : 0,
            "query": {
               "match_all": {}
            },
            "script_score" : {
                "script" : {
                  "lang" : "skiplist",
                  "source" : "roaring",
                  "params" : {
                      "field" : "_id",
                      "data" : "$data"
                  }
                }
            }
        }
    }
}
'
```