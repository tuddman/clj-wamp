(ns clj-wamp.client.subscriber
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub put! take!]]
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
  [{:keys [debug? subscriptions] :as instance}]
  (let [[unregistered _ pending] @subscriptions
        unregistered-chan (chan 1)]
    (go (>! unregistered-chan (<! unregistered)))
    (when debug?
      (log/debug "[subscribe-next!-new] Register "))
    (try
      (go
        (let [[sub-id reg-uri] (<! unregistered)]
          (>! pending [sub-id])
          (subscribe instance sub-id {} reg-uri)
          )

        )
      (catch Exception e
        (taoensso.timbre/error e)))
    ;(go-loop []
    ;  (let [[sub-id reg-uri] (<! unregistered)]
    ;    (>! pending [sub-id])
    ;    (subscribe instance sub-id {} reg-uri)
    ;    (recur))
    ;
    ;  )
    )
  )

(defn subscribe-new
  [{:keys [debug? subscriptions] :as instance} reg-uri reg-channel]
  (let [[unregistered registered pending] @subscriptions]
    (when debug?
      (log/debug "[subscribe-new] Register " reg-uri))
    (if-not (lib/contains-nested? registered #(= % reg-uri))
      (println "putting")
      (let [sub-id (core/new-rand-id)]
        (put! unregistered {sub-id reg-uri}))
      )
    )
  ;(fn [args kw-args meta-data]
  ;  (let [data {:args      args
  ;              :kw-args   kw-args
  ;              :meta-data meta-data}]
  ;    (put! channel data)))
  ;
  ;[{:keys [debug?] :as instance} reg-uri reg-fn]
  ;(swap! (:subscriptions instance)
  ;       (fn [[unregistered registered pending :as registrations]]
  ;         (when debug?
  ;           (log/debug "[register-new!] Register " reg-uri))
  ;         (if-not (lib/contains-nested? (lib/map->vec registrations) #(= % reg-uri))
  ;           [(assoc unregistered reg-uri reg-fn) registered pending]
  ;           [unregistered registered pending])))
  ;
  ;(when debug?
  ;  (let [[unregistered _ _] @(:subscriptions instance)]
  ;    (log/debug "[subscribe-new!] unregistered subscriptions " unregistered)
  ;    ))
  ;
  ;(subscribe-next! instance)
  )


