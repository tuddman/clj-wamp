(ns clj-wamp.client.subscriber
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub put!]]
    [taoensso.timbre :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.core :as core]
    [clj-wamp.client.v2 :refer [send!]]
    [clj-wamp.libs.helpers :as lib]
    [clj-wamp.info.ids :refer [reverse-message-id message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.client.handler.error :refer [handle-error]]
    [clj-wamp.libs.channels :refer [messages]]
    ))


(defn subscribe
  "[SUBSCRIBE, Request|id, Options|dict, Topic|uri]"
  [instance request-id options uri]
  (send! instance [(message-id :SUBSCRIBE) request-id options uri]))

(defn unsubscribe
  "[UNSUBSCRIBE, Request|id, SUBSCRIBED.Subscription|id]"
  [instance request-id reg-uri]
  (send! instance [(message-id :UNSUBSCRIBE) request-id reg-uri]))

(defn subscribe-next!
  [{:keys [debug?] :as instance}]
  )

(defn subscribe-new
  [{:keys [debug?] :as instance} reg-uri reg-channel]
  (fn [args kw-args meta-data]
    (let [data {:args      args
                :kw-args   kw-args
                :meta-data meta-data}]
      (put! channel data)))

  [{:keys [debug?] :as instance} reg-uri reg-fn]
  (swap! (:subscriptions instance)
         (fn [[unregistered registered pending :as registrations]]
           (when debug?
             (log/debug "[register-new!] Register " reg-uri))
           (if-not (lib/contains-nested? (lib/map->vec registrations) #(= % reg-uri))
             [(assoc unregistered reg-uri reg-fn) registered pending]
             [unregistered registered pending])))

  (when debug?
    (let [[unregistered _ _] @(:subscriptions instance)]
      (log/debug "[subscribe-new!] unregistered subscriptions " unregistered)
      ))

  (subscribe-next! instance)
  )


