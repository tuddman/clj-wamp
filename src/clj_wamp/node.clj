(ns ^{:author "Ryan Sundberg"
      :doc "WAMP V2 application node"}
  clj-wamp.node
  (:require
    [taoensso.timbre :as log]
    [cheshire.core :as json]
    [gniazdo.core :as ws]
    [clj-wamp.core :as core]
    [clj-wamp.client.v2 :as wamp]
    [clj-wamp.client.handler.messages :as hm]
    [clj-wamp.client.publisher :refer [publish]]
    [clj-wamp.client.subscriber :as subscriber]
    [clj-wamp.client.callee :as callee]
    [clj-wamp.client.caller :as caller]
    [clj-wamp.libs.channels :refer [incoming-messages]]
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout sub put!]])
  (:import
    [org.eclipse.jetty.websocket.client WebSocketClient]
    (com.fasterxml.jackson.core JsonParseException)
    (java.net URI)))



(defn publish!
  "Publish an event"
  ([instance event-uri]
   (publish! instance event-uri nil))
  ([instance event-uri seq-args]
   (publish! instance event-uri seq-args nil))
  ([instance event-uri seq-args kw-args]
   (publish instance (core/new-rand-id) {} event-uri seq-args kw-args)))

(defn publish-to!
  "Publish an event to specific session ids"
  [instance session-ids event-uri seq-args kw-args]
  (publish instance (core/new-rand-id) {:eligible session-ids} event-uri seq-args kw-args))

(defn call!
  "Call a procedure"
  ([instance event-uri]
   (call! instance event-uri nil))
  ([instance event-uri seq-args]
   (call! instance event-uri seq-args nil))
  ([instance event-uri seq-args kw-args]
   (caller/call instance (core/new-rand-id) {} event-uri seq-args kw-args)))

(defn call-to!
  "Publish an event to specific session ids"
  [instance session-ids event-uri seq-args kw-args]
  (caller/call instance (core/new-rand-id) {:eligible session-ids} event-uri seq-args kw-args))

(defn register!
  "Register an procedure"
  [instance event-uri event-fn]
  (callee/register-new! instance
                      event-uri
                      (fn [args] (let [seq-args (:seq-args args)]
                                   (apply event-fn seq-args)))))

(defn unregister!
  "Unregister an procedure"
  [instance event-uri]
  (callee/unregister! instance event-uri))

(defn unregister-all!
  [instance]
  (let [[_ registered _] @(:registrations instance)]
    (for [reg-prod registered
          :let [reg-id (first reg-prod)
                reg-uri (first (get registered reg-id))]]
      (unregister! instance reg-uri)
      )))

(defn subscribe!
  "Subscribe to an Event"
  [instance event-uri]
  (subscriber/subscribe-new! instance event-uri))

(defn unsubscribe!
  "Unsubscribe of an Event"
  [instance event-uri]
  (subscriber/unsubscribe! instance event-uri))

(defn unsubscribe-all!
	[instance]
	(let [{:keys [registered]} @(:subscriptions instance)]
		(for [reg-sub @registered
					:let [reg-id (first reg-sub)
								reg-uri (first (get @registered reg-id))]]
			(unsubscribe! instance reg-uri)
			)))

(defn- handle-connect
  [{:keys [debug? registrations subscriptions reg-on-call sub-on-call] :as _} session]
  (when debug?
    (log/debug "Connected to WAMP router with session" session))
  ; there might be a race condition here if we get a "welcome" message before the connect event
  (reset! registrations [reg-on-call {} {}])
  (reset! subscriptions {:unregistered (chan)
												 :pending      (chan)
												 :registered   (atom nil)
												 })
	(if-not (nil? sub-on-call)
		(put! (:unregistered subscriptions) sub-on-call))
  )

(defn- handle-message
  [{:keys [debug?] :as instance} msg-str]
  (let [msg-data (try (json/decode msg-str)
                      (catch JsonParseException _
                        [nil nil]))]
    (when debug?
      (log/debug "WAMP message received:" msg-str))
    (hm/handle-message instance msg-data)))

(declare connect!)

(defn- handle-close
  [{:keys [debug?] :as instance} code reason]
  (when debug?
    (log/debug "Disconnected from WAMP router:" code reason))
	(try
		(unregister-all! instance)
		(unsubscribe-all! instance)
		(catch Exception e
			(log/error "Unable to unregister/unsubscribe on close " e)))

  (reset! (:socket instance) nil)
  (when @(:reconnect-state instance)
    (connect! instance)))

(defn- handle-error
  [_ ex]
  (log/error ex "WAMP socket error"))

(defn- try-connect [{:keys [debug? router-uri] :as instance}]
  (try
    (swap! (:socket instance)
           (fn [socket]
             (when (nil? socket)
               (when debug?
                 (log/debug "Connecting to WAMP router at" router-uri))
               (let [socket (ws/connect
                              router-uri
                              :client (:client instance)
                              :headers {}
                              :subprotocols [wamp/subprotocol-id]
                              :on-connect (partial handle-connect instance)
                              :on-receive (partial handle-message instance)
                              :on-close (partial handle-close instance)
                              :on-error (partial handle-error instance))]
                 socket))))
    (wamp/hello instance)
    true
    (catch Exception e
      (log/error e "Failed to connect to WAMP router")
      false)))

(defn connect! [{:keys [reconnect-state reconnect? reconnect-wait-ms] :as instance}]
  (reset! reconnect-state reconnect?)
  (let [connected? (try-connect instance)]
    (if connected?
      instance
      (if @reconnect-state
        (do
          (Thread/sleep reconnect-wait-ms)
          (recur instance))
        instance))))

(defn disconnect! [{:keys [debug?] :as instance}]
  (reset! (:reconnect-state instance) false)
	(try
		(unregister-all! instance)
		(unsubscribe-all! instance)
		(catch Exception e
			(log/error "Unable to unregister/unsubscribe on close " e)))
  (swap! (:socket instance)
         (fn [socket]
           (when (some? socket)
             (when debug?
               (log/debug "Disconnecting from WAMP router"))
             (ws/close socket)
             nil))))

(defn create [{:keys [router-uri realm _] :as conf}]
  {:pre [(string? router-uri)
         (string? realm)]}
  (let [client (ws/client (URI. router-uri))]
    (.start ^WebSocketClient client)
    (merge
      {:debug? false
       :reconnect? true
       :reconnect-wait-ms 10000}
      conf
      {:client client
       :socket (atom nil)
       :reconnect-state (atom false)
       :registrations (atom nil)
			 :subscriptions (atom {:unregistered (chan)
														 :pending      (chan)
														 :registered   (atom nil)
														 })})))
