(ns clj-wamp.client.auth
  (:require
    [clj-wamp.client.v2 :refer [send! error exception-message]]
    [clj-wamp.info.ids :refer [message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.client.handler.error :refer [handle-error]]
    [clojure.data.codec.base64 :as b64]
    [taoensso.timbre :as log]
    [pandect.algo.sha256 :refer [sha256-hmac-bytes]]))

(defn generateHash
  [string secret]
  (-> (sha256-hmac-bytes string secret)
      (b64/encode)
      (String. "UTF-8")))

(defmulti handle-authenticate (fn [_ auth-method _] (keyword auth-method)))

(defmethod handle-authenticate :wampcra
  [{:keys [debug? auth-details] :as instance} _ extra]

  (let [secretKey (get auth-details :secret)
        challengeString (get extra "challenge")
        hash (generateHash challengeString secretKey)]

    (when debug?
      (log/debug "[authenticate] Generated Hash " hash))

    (send! instance [(message-id :AUTHENTICATE) hash {}])))

