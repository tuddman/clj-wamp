(ns clj-wamp.client.handler.messages
  (:require
    [clojure.core.async :refer [go >!]]
    [taoensso.timbre :as log]
    [gniazdo.core :as ws]
    [clj-wamp.client.v2 :refer [error goodbye]]
    [clj-wamp.info.ids :refer [reverse-message-id message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.client.handler.error :refer [handle-error]]
    [clj-wamp.libs.channels :refer [messages]]
    [clj-wamp.client.callee :refer [perform-invocation register-next!]]
    [clj-wamp.client.subscriber :refer [subscribe-next!]]
    ))

(defmulti handle-message (fn [_ data] (reverse-message-id (first data))))

(defmethod handle-message nil
  [instance _]
  (error instance 0 0 {:message "Invalid message type"} (error-uri :bad-request)))

(defmethod handle-message :WELCOME
  [instance _]
  (register-next! instance)
  (subscribe-next! instance)
  )

(defmethod handle-message :ABORT
  [instance _]
  (log/warn "Received ABORT message from router")
  (when-let [socket @(:socket instance)]
    (ws/close socket)))

(defmethod handle-message :GOODBYE
  [instance _]
  (goodbye instance {} (error-uri :goodbye-and-out))
  (when-let [socket @(:socket instance)]
    (ws/close socket)))

(defmethod handle-message :ERROR
  [instance data]
  "[ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri]
   [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list]
   [ERROR, REQUEST.Type|int, REQUEST.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]"
  (handle-error instance data))

(defmethod handle-message :PUBLISHED
  [_ _]
  nil)

(defmethod handle-message :REGISTERED
  [{:keys [debug?] :as instance} data]
  "[REGISTERED, REGISTER.Request|id, Registration|id]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 1)
                 reg-id (nth data 2)
                 [reg-uri reg-fn] (get pending req-id)]
             (when debug?
               (log/debug "Registered " reg-uri))
             [unregistered (assoc registered reg-id [reg-uri reg-fn]) (dissoc pending req-id)])))
  (when debug?
    (let [[_ registered pending] @(:registrations instance)]
      (log/debug "registered procedures " registered)
      ))
  ;(register-next! instance)
  nil
  )

(defmethod handle-message :UNREGISTERED
  [{:keys [debug?] :as instance} data]
  "[UNREGISTERED, UNREGISTER.Request|id]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 1)
                 [reg-id reg-uri] (get pending req-id)]
             (when debug?
               (log/debug "Unregistered " reg-uri))
             [unregistered (dissoc registered reg-id) (dissoc pending req-id)]))
         )
  (when debug?
    (let [[_ registered _] @(:registrations instance)]
      (log/debug "registered procedures " registered)
      ))
  nil)

(defmethod handle-message :INVOCATION
  [instance data]
  "[INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list, CALL.ArgumentsKw|dict]"
  (let [[_ registered _] @(:registrations instance)
        req-id (nth data 1)
        reg-id (nth data 2)
        [_ reg-fn] (get registered reg-id)]
    (if (some? reg-fn)
      (perform-invocation instance req-id reg-fn (nth data 3) (nth data 4 []) (nth data 5 nil))
      (error instance (message-id :INVOCATION) req-id
             {:message "Unregistered RPC"
              :reg-id reg-id}
             (error-uri :no-such-registration)))))

(defmethod handle-message :RESULT
  [_ data]
  "Receives the Result Message after an call. Publishes it via core async, using the topic :RESULT
   [RESULT, CALL.Request|id, Details|dict]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list]
   [RESULT, CALL.Request|id, Details|dict, YIELD.Arguments|list, YIELD.ArgumentsKw|dict]"
  (let [[_ req-id details arguments arguments-kw] data]
    (go (>! messages {:topic :RESULT :req-id req-id :details details, :arguments arguments, :arguments-kw arguments-kw})))
  )

(defmethod handle-message :SUBSCRIBED
  [instance data]
  "[SUBSCRIBED, SUBSCRIBE.Request|id, Subscription|id]"
  (log/debug data))


(defmethod handle-message :UNSUBSCRIBED
  [instance data]
  "[UNSUBSCRIBED, UNSUBSCRIBE.Request|id]"
  (log/debug data))


(defmethod handle-message :EVENT
  [instance data]
  "[EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id, Details|dict]
  [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id, Details|dict, PUBLISH.Arguments|list]
  [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id, Details|dict, PUBLISH.Arguments|list, PUBLISH.ArgumentKw|dict]"
  (log/debug data))