(ns abci.impl.codec
  (:import [io.netty.buffer ByteBuf]))

(defn read-varint64 [^ByteBuf buf]
  (loop [shift 0
         out   0]
    (when (.isReadable buf)
      (if (< shift 64)
        (let [b   (.readByte buf)
              out (bit-or out (bit-shift-left
                               (unchecked-long (bit-and b 0x7F))
                               shift))]
          (if (zero? (bit-and b 0x80))
            out
            (recur (+ shift 7) out)))
        (throw (ex-info "Invalid varint." {:shift shift}))))))

(defn long->varint64 [n]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop [n n]
      (if (zero? (bit-and n (bit-not 0x7F)))
        (do (.write out (unchecked-int n))
            (.toByteArray out))
        (do
          (.write out (bit-or (bit-and n 0x7F) 0x80))
          (recur (unsigned-bit-shift-right n 7)))))))
