# clj-aws-s3

A Clojure library for accessing Amazon S3, based on the official AWS
Java SDK.

Although there are a few S3 clients for Clojure around, this library
aims to provide a more complete implementation, with metadata, streams
and protocols for uploading different types of data.

Currently the library supports functions to create and delete buckets,
and to list, get, and put objects and their metadata.

It doesn't support ACLs yet, but will do in a future release.

## Install

No release for this fork yet. Clone it or forget it.

## Example

### Basics 

```clojure
(require '[aws.sdk.s3 :as s3])

(def cred {:access-key "...", 
	   :secret-key "..."})

(s3/create-bucket cred "my-bucket")

(s3/put-object cred "my-bucket" "some-key" "some-value")

(println (slurp (:content (s3/get-object cred "my-bucket" "some-key"))))

```

### Multipart upload without client-side encryption 

```clojure

(require '[aws.sdk.s3 :as s3])

(def cred {:access-key "...", 
           :secret-key "..."})

(def tm (s3/s3-transfer-manager (s3/s3-client cred))) 

; no client side encryption, no server-side encryption
(s3/upload-file tm "my-bucket" "some-key" "/path/to/file")

; no client side encryption, AES-256 server-side encryption
(s3/upload-file-sse tm "my-bucket" "some-key" "/path/to/file")

```

### Multipart upload with RSA client-side encryption

You can create your keystore anywhere, a hidden folder in your home dir is a good starting place.
The following will both create a keystore and add an asymetric RSA pair to it: 

```bash
keytool -genkey -keyalg RSA 
        -dname "cn=John Doe, ou=IT, o=Acme, c=FR" 
        -alias my-alias -keystore /path/to/keystore 
        -storepass my-ks-password -validity 365
```

Then simply add the keystore and key info to the cred map.

```clojure

(require '[aws.sdk.s3 :as s3])

(def cred {:access-key "...",
           :secret-key "...",
           :ks-path    "/path/to/keystore"
           :ks-pwd     "my-ks-password"
           :key-alias  "my-alias"
           :key-pwd    "my-key-password" })

(def tm (s3/s3-transfer-manager (s3/s3-client-encryption cred)))

; RSA client side encryption, no SSE
(s3/upload-file tm "my-bucket" "some-key" "/path/to/file")

; RSA client side encryption + AES-256 server-side encryption
(s3/upload-file-sse tm "my-bucket" "some-key" "/path/to/file")

```

## Documentation

* [API docs](http://weavejester.github.com/clj-aws-s3/)

## License

Copyright (C) 2012 James Reeves / François Le Lay

Distributed under the Eclipse Public License, the same as Clojure.

