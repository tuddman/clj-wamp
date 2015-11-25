(ns clj-wamp.handler.messages
  (:require
    [clojure.core.async :refer [go >!]]
    [taoensso.timbre :as log]
    [gniazdo.core :as ws]
    [clj-wamp.v2 :refer [perform-invocation error goodbye register-next!]]
    [clj-wamp.info.ids :refer [reverse-message-id message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.handler.error :refer [handle-error]]
    [clj-wamp.channels :refer [messages]]
    ))

(defmulti handle-message (fn [instance data] (reverse-message-id (first data))))

(defmethod handle-message nil
  [instance data]
  (error instance 0 0 {:message "Invalid message type"} (error-uri :bad-request)))

(defmethod handle-message :WELCOME
  [instance data]
  (println instance)
  (register-next! instance))

(defmethod handle-message :ABORT
  [instance data]
  (log/warn "Received ABORT message from router")
  (when-let [socket @(:socket instance)]
    (ws/close socket)))

(defmethod handle-message :GOODBYE
  [instance data]
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
  [instance data]
  nil)

(defmethod handle-message :REGISTERED
  [instance data]
  "[REGISTERED, REGISTER.Request|id, Registration|id]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 1)
                 reg-id (nth data 2)
                 [reg-uri reg-fn] (get pending req-id)]
             [unregistered (assoc registered reg-id reg-fn) (dissoc pending req-id)])))
  (register-next! instance))

(defmethod handle-message :INVOCATION
  [instance data]
  "[INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list]
   [INVOCATION, Request|id, REGISTERED.Registration|id, Details|dict, CALL.Arguments|list, CALL.ArgumentsKw|dict]"
  (let [[_ registered _] @(:registrations instance)
        req-id (nth data 1)
        reg-id (nth data 2)
        reg-fn (get registered reg-id)]
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
