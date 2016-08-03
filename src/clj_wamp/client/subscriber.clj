(ns clj-wamp.client.subscriber
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub put! take!]]
    [clj-wamp.client.node :refer [new-rand-id send!]]
    [clj-wamp.client.handler.error :refer [handle-error]]  
    [clj-wamp.libs.helpers :as lib]
    [clj-wamp.info.ids :refer [message-id reverse-message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [taoensso.timbre :as log] ))


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
			(let [[sub-id reg-uri event-chan] (<! unregistered)]
				(put! pending [sub-id reg-uri event-chan])
				(when debug?
					(log/debug "[subscribe-next!] Subscribing " sub-id reg-uri))
				(subscribe instance sub-id {} reg-uri)
				(recur)))))


(defn subscribe-new!
	[{:keys [debug? subscriptions] :as instance} reg-uri event-chan]
	(let [{:keys [unregistered registered]} @subscriptions]
		(when debug?
			(log/debug "[subscribe-new] Register " reg-uri))
		(if-not (lib/contains-nested? registered #(= % reg-uri))
			(let [req-id (new-rand-id)]
				(go (>! unregistered [req-id reg-uri event-chan]))))))


(defn unsubscribe!
	"Deassociates the procedure with the id and sends the unregister message to the server"
	[{:keys [debug? subscriptions] :as instance} reg-uri]
	(let [{:keys [registered pending]} @subscriptions]
		(when debug?
			(log/debug "[unsubscribe!] Unsubscribe " reg-uri))
		(if-let [[reg-id [_ _]] (contains? @registered reg-uri)]
			(let [req-id (new-rand-id)]
				(put! pending [reg-id reg-uri])
				(unsubscribe instance req-id reg-id))))

	(when debug?
		(let [[_ _ pending] @(:registrations instance)]
			(log/debug "[unregister!] pending procedures " pending))))

