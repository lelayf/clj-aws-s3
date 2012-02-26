(ns aws.sdk.keypair
  (:import java.security.KeyPair
           java.security.KeyStore
           java.security.KeyStore$PasswordProtection
           java.security.PrivateKey
           java.security.PublicKey
           java.io.File
           java.io.FileInputStream))

(defn load-keystore
  "Load Java Keystore. You can create your keystore and keys with
  the keytool utility."
  [kspath kspwd]
  (let [ks (KeyStore/getInstance "JKS")
        fis (FileInputStream. kspath)]
    (.load ks fis (char-array (.length kspwd) kspwd))
    (.close fis)
    ks))

(defn get-key-pair
  "After loading your keystore, you can get a KeyPair
  matched by alias and unlocked by password. This pair
  can then be used to instantiate EncryptionMaterials
  needed by AmazonS3EncryptionClient." 
  [keystore key-alias key-password]
  (let [pkEntry (.getEntry keystore 
                           key-alias 
                           (KeyStore$PasswordProtection. (char-array (.length key-password) key-password)))
        pr-key (.getPrivateKey pkEntry)
        cert (.getCertificate pkEntry)
        pub-key (.getPublicKey cert)]
    (KeyPair. pub-key pr-key)))

