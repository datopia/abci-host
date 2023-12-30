(ns abci.datopia.ed25519
  (:require [clojure.java.io :refer [input-stream]])
  (:import [java.security
            SecureRandom
            Security]
           [org.bouncycastle.crypto.generators
            Ed25519KeyPairGenerator]
           [org.bouncycastle.crypto.params
            Ed25519KeyGenerationParameters
            Ed25519PrivateKeyParameters
            Ed25519PublicKeyParameters]
           [org.bouncycastle.crypto.signers
            Ed25519Signer]
           [org.bouncycastle.jce.provider
            BouncyCastleProvider]
           [org.bouncycastle.crypto.digests
            SHA3Digest]))

(Security/addProvider (BouncyCastleProvider.))

(defn digest [input]
  (let [buf         (byte-array 1024)
        digest      (SHA3Digest.)
        hash-buffer (byte-array (.getDigestSize digest))]
    (with-open [in (input-stream input)]
      (loop [n (.read in buf)]
        (if (<= n 0)
          (do
            (.doFinal digest hash-buffer 0)
            hash-buffer)
          (recur (do
                   (.update digest buf 0 n)
                   (.read in buf))))))))

(defn generate-keypair []
  (let [random (SecureRandom.)
        kpg    (Ed25519KeyPairGenerator.)]
    (.init kpg (Ed25519KeyGenerationParameters. random))
    (let [key-pair (.generateKeyPair kpg)
          private  ^Ed25519PrivateKeyParameters (.getPrivate key-pair)
          public   ^Ed25519PublicKeyParameters  (.getPublic key-pair)]
      {:private (.getEncoded private)
       :public  (.getEncoded public)})))

(defn- signer [private-key]
  (doto (Ed25519Signer.)
    (.init true (Ed25519PrivateKeyParameters. private-key 0))))

(defn- verifier [public-key]
  (doto (Ed25519Signer.)
    (.init false (Ed25519PublicKeyParameters. public-key 0))))

(defprotocol Signer
  (sign [_ msg-bytes]))

(extend-type Ed25519Signer
  Signer
  (sign [signer msg-bytes]
    (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
    (.generateSignature signer)))

(extend-type (type (byte-array 0))
  Signer
  (sign [private-key msg-bytes]
    (sign (signer private-key) msg-bytes)))

(defprotocol Verifier
  (signed? [_ msg-bytes sig]))

(extend-type Ed25519Signer
  Verifier
  (signed? [signer msg-bytes sig]
    (.update signer msg-bytes 0 (alength ^bytes msg-bytes))
    (.verifySignature signer sig)))

(extend-type (type (byte-array 0))
  Verifier
  (signed? [public-key msg-bytes sig]
    (signed? (verifier public-key) msg-bytes sig)))
