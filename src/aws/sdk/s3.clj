(ns aws.sdk.s3
  "Functions to access the Amazon S3 storage service.
   Supports client side encryption (tested with RSA key generated
   with keytool). Also supports server-side encryption. Ability
   to upload files bigger than 5GB when using multipart."
  (:require [aws.sdk.keypair :as kp])
  (:import com.amazonaws.auth.BasicAWSCredentials
           com.amazonaws.services.s3.transfer.Transfer
           com.amazonaws.services.s3.transfer.TransferManager
           com.amazonaws.services.s3.transfer.Upload
           com.amazonaws.services.s3.model.ObjectMetadata
           com.amazonaws.services.s3.AmazonS3Client
           com.amazonaws.services.s3.AmazonS3EncryptionClient
           com.amazonaws.services.s3.model.EncryptionMaterials
           com.amazonaws.AmazonServiceException
           com.amazonaws.services.s3.model.ListObjectsRequest
           com.amazonaws.services.s3.model.ObjectMetadata
           com.amazonaws.services.s3.model.ObjectListing
           com.amazonaws.services.s3.model.PutObjectRequest
           com.amazonaws.services.s3.model.S3Object
           com.amazonaws.services.s3.model.S3ObjectSummary
           java.io.ByteArrayInputStream
           java.io.File
           java.io.InputStream
           java.io.FileInputStream
           java.nio.charset.Charset))

(defn s3-client
  "Create an AmazonS3Client instance from a map of credentials."
  [cred]
  (AmazonS3Client.
   (BasicAWSCredentials.
    (:access-key cred)
    (:secret-key cred))))

(defn s3-encryption-client
  "Create an AmazonS3EncryptionClient from a map of credentials.
   Specify your keystore path, keystore password, key alias and
   key password in the map."
  [cred]
  (let [ks (kp/load-keystore (:ks-path cred) (:ks-pwd cred))
        keypair (kp/get-key-pair ks (:key-alias cred) (:key-pwd cred))]
    (AmazonS3EncryptionClient.
      (BasicAWSCredentials. (:access-key cred) (:secret-key cred))
      (EncryptionMaterials. keypair))))

(defn s3-transfer-manager
  "Create an AmazonS3 TransferManager from an s3 client.
   The passed-in client can support client-side encryption or not."
  [s3client]
  (TransferManager. s3client)) 

(defn bucket-exists?
  "Returns true if the supplied bucket name already exists in S3."
  [cred name]
  (.doesBucketExist (s3-client cred) name))

(defn create-bucket
  "Create a new S3 bucket with the supplied name."
  [cred name]
  (.createBucket (s3-client cred) name))

(defn delete-bucket
  "Delete the S3 bucket with the supplied name."
  [cred name]
  (.deleteBucket (s3-client cred) name))

(defn upload-file
  "Multipart upload of a file using TransferManager. 
   File size possibly bigger than 5GB, no server-side encryption"
  [tm bucket filename filepath]
  (let [f (File. filepath)
        len (.length f)
        elapsed (atom 0)
        sleep-duration 2
        upload (.upload tm bucket filename f)]
    (print (str "TRANSFER: " (.getDescription  upload)))
    (println (str " (" len " bytes)"))
    (while (not (.isDone upload))
      (print (str "\tSTATE: " (.getState upload)))
      (print (str "\tPROGRESS: " (format "%.2f" (.getPercentTransfered (.getProgress upload)))))
      (println (str "\tELAPSED: " @elapsed " sec"))
      (. Thread sleep (* sleep-duration 1000))
      (swap! elapsed + sleep-duration))))

(defn upload-file-sse
  "Multipart upload of a file using TransferManager. 
   Uses server-side encryption (AES-256)"
  [tm bucket filename filepath]
  (let [f (File. filepath)
        len (.length f)
        fis (FileInputStream. f)
        objmeta (doto (ObjectMetadata.) 
                  (.setContentLength len)
                  (.setServerSideEncryption (ObjectMetadata/AES_256_SERVER_SIDE_ENCRYPTION)))
        elapsed (atom 0)
        sleep-duration 2
        upload (.upload tm 
                        bucket 
                        filename 
                        fis 
                        objmeta)]
    (print (str "TRANSFER: " (.getDescription  upload)))
    (println (str " (" len " bytes)"))
    (while (not (.isDone upload))
      (print (str "\tSTATE: " (.getState upload)))
      (print (str "\tPROGRESS: " (format "%.2f" (.getPercentTransfered (.getProgress upload)))))
      (println (str "\tELAPSED: " @elapsed " sec"))
      (. Thread sleep (* sleep-duration 1000))
      (swap! elapsed + sleep-duration))))


(defprotocol ^{:no-doc true} ToPutRequest
  "A protocol for constructing a map that represents an S3 put request."
  (^{:no-doc true} put-request [x] "Convert a value into a put request."))

(extend-protocol ToPutRequest
  InputStream
  (put-request [is] {:input-stream is})
  File
  (put-request [f] {:file f})
  String
  (put-request [s]
    {:input-stream     (ByteArrayInputStream. (.getBytes s))
     :content-length   (count s)
     :content-encoding (.name (Charset/defaultCharset))}))

(defmacro set-attr
  "Set an attribute on an object if not nil."
  {:private true}
  [object setter value]
  `(if-let [v# ~value]
     (~setter ~object v#)))

(defn- map->ObjectMetadata
  "Convert a map of object metadata into a ObjectMetadata instance."
  [metadata]
  (doto (ObjectMetadata.)
    (set-attr .setCacheControl         (:cache-control metadata))
    (set-attr .setContentDisposition   (:content-disposition metadata))
    (set-attr .setContentEncoding      (:content-encoding metadata))
    (set-attr .setContentLength        (:content-length metadata))
    (set-attr .setContentMD5           (:content-md5 metadata))
    (set-attr .setContentType          (:content-type metadata))
    (set-attr .setServerSideEncryption (:server-side-encryption metadata))
    (set-attr .setUserMetadata
     (dissoc metadata :cache-control
                      :content-disposition
                      :content-encoding
                      :content-length
                      :content-md5
                      :content-type
                      :server-side-encryption))))

(defn- ->PutObjectRequest
  "Create a PutObjectRequest instance from a bucket name, key and put request
  map."
  [bucket key request]
  (cond
   (:file request)
     (PutObjectRequest. bucket key (:file request))
   (:input-stream request)
     (PutObjectRequest.
      bucket key
      (:input-stream request)
      (map->ObjectMetadata (dissoc request :input-stream)))))

(defn put-object
  "Put a value into an S3 bucket at the specified key. The value can be
  a String, InputStream or File (or anything that implements the ToPutRequest
  protocol). Seems to break when uploading files bigger than 5GB.
  Does not support client side encryption."
  [cred bucket key value]
  (->> (put-request value)
       (->PutObjectRequest bucket key)
       (.putObject (s3-client cred))))

(defn put-object-client
  "Same as put-object but pass it a configured s3 client instead of creds.
  Aim was to be able to use client side encryption but with no success.
  It works fine with the regular s3 client though."
  [client bucket key value]
  (->> (put-request value)
       (->PutObjectRequest bucket key)
       (.putObject client)))


(defprotocol ^{:no-doc true} Mappable
  "Convert a value into a Clojure map."
  (^{:no-doc true} to-map [x] "Return a map of the value."))

(extend-protocol Mappable
  S3Object
  (to-map [object]
    {:content  (.getObjectContent object)
     :metadata (to-map (.getObjectMetadata object))
     :bucket   (.getBucketName object)
     :key      (.getKey object)})
  ObjectMetadata
  (to-map [metadata]
    {:cache-control          (.getCacheControl metadata)
     :content-disposition    (.getContentDisposition metadata)
     :content-encoding       (.getContentEncoding metadata)
     :content-length         (.getContentLength metadata)
     :content-md5            (.getContentMD5 metadata)
     :content-type           (.getContentType metadata)
     :etag                   (.getETag metadata)
     :last-modified          (.getLastModified metadata)
     :server-side-encryption (.getServerSideEncryption metadata)})
  ObjectListing
  (to-map [listing]
    {:bucket          (.getBucketName listing)
     :objects         (map to-map (.getObjectSummaries listing))
     :prefix          (.getPrefix listing)
     :common-prefixes (seq (.getCommonPrefixes listing))
     :truncated?      (.isTruncated listing)
     :max-keys        (.getMaxKeys listing)
     :marker          (.getMarker listing)
     :next-marker     (.getNextMarker listing)})
  S3ObjectSummary
  (to-map [summary]
    {:metadata {:content-length (.getSize summary)
                :etag           (.getETag summary)
                :last-modified  (.getLastModified summary)}
     :bucket   (.getBucketName summary)
     :key      (.getKey summary)}))

(defn get-object
  "Get an object from an S3 bucket. The object is returned as a map with the
  following keys:
    :content  - an InputStream to the content
    :metadata - a map of the object's metadata
    :bucket   - the name of the bucket
    :key      - the object's key"
  [cred bucket key]
  (to-map (.getObject (s3-client cred) bucket key)))

(defn get-object-metadata
  "Get an object's metadata from a bucket. The metadata is a map with the
  following keys:
    :cache-control          - the CacheControl HTTP header
    :content-disposition    - the ContentDisposition HTTP header
    :content-encoding       - the character encoding of the content
    :content-length         - the length of the content in bytes
    :content-md5            - the MD5 hash of the content
    :content-type           - the mime-type of the content
    :etag                   - the HTTP ETag header
    :last-modified          - the last modified date
    :server-side-encryption - the server-side encryption algorithm"
  [cred bucket key]
  (to-map (.getObjectMetadata (s3-client cred) bucket key)))

(defn- map->ListObjectsRequest
  "Create a ListObjectsRequest instance from a map of values."
  [request]
  (doto (ListObjectsRequest.)
    (set-attr .setBucketName (:bucket request))
    (set-attr .setDelimiter  (:delimiter request))
    (set-attr .setMarker     (:marker request))
    (set-attr .setMaxKeys    (:max-keys request))
    (set-attr .setPrefix     (:prefix request))))

(defn list-objects
  "List the objects in an S3 bucket. A optional map of options may be supplied.
  Available options are:
    :delimiter - read only keys up to the next delimiter (such as a '/')
    :marker    - read objects after this key
    :max-keys  - read only this many objects
    :prefix    - read only objects with this prefix

  The object listing will be returned as a map containing the following keys:
    :bucket          - the name of the bucket
    :prefix          - the supplied prefix (or nil if none supplied)
    :objects         - a list of objects
    :common-prefixes - the common prefixes of keys omitted by the delimiter
    :max-keys        - the maximum number of objects to be returned
    :truncated?      - true if the list of objects was truncated
    :marker          - the marker of the listing
    :next-marker     - the next marker of the listing"
  [cred bucket & [options]]
  (to-map
   (.listObjects
    (s3-client cred)
    (map->ListObjectsRequest (merge {:bucket bucket} options)))))

(defn delete-object
  "Delete an object from an S3 bucket."
  [cred bucket key]
  (.deleteObject (s3-client cred) bucket key))

(defn object-exists?
  "Returns true if an object exists in the supplied bucket and key."
  [cred bucket key]
  (try
    (get-object-metadata cred bucket key)
    true
    (catch AmazonServiceException e
      (if (= 404 (.getStatusCode e))
        false
        (throw e)))))

(defn copy-object
  "Copy an existing S3 object to another key."
  ([cred bucket src-key dest-key]
     (copy-object cred bucket src-key bucket dest-key))
  ([cred src-bucket src-key dest-bucket dest-key]
     (.copyObject (s3-client cred) src-bucket src-key dest-bucket dest-key)))
