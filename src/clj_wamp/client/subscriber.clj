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
  (let [{:keys [unregistered pending]} @subscriptions]
    (when debug?
      (log/debug "[subscribe-next!] Register " unregistered))
		(go-loop []
			(let [[sub-id reg-uri] (<! unregistered)]
				(put! pending [sub-id reg-uri])
				(when debug?
					(log/debug "[subscribe-next!] Subscribing " sub-id reg-uri))
				(subscribe instance sub-id {} reg-uri)
				(recur)))))

(defn subscribe-new!
	[{:keys [debug? subscriptions] :as instance} reg-uri]
	(let [{:keys [unregistered registered]} @subscriptions]
		(when debug?
			(log/debug "[subscribe-new] Register " reg-uri))
		(if-not (lib/contains-nested? registered #(= % reg-uri))
			(let [req-id (core/new-rand-id)]
				(go (>! unregistered [req-id reg-uri]))))))

(defn unsubscribe!
	"Deassociates the procedure with the id and sends the unregister message to the server"
	[{:keys [debug? subscriptions] :as instance} reg-uri]
	(let [{:keys [registered pending]} @subscriptions]
		(when debug?
			(log/debug "[unsubscribe!] Unsubscribe " reg-uri))
		(if-let [[reg-id [_ _]] (lib/finds-nested @registered reg-uri)]
			(let [req-id (core/new-rand-id)]
				(put! pending [reg-id reg-uri])
				(unsubscribe instance req-id reg-id)
				)))

	(when debug?
		(let [[_ _ pending] @(:registrations instance)]
			(log/debug "[unregister!] pending procedures " pending)
			))
	)

