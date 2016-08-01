(ns clj-wamp.client.handler.error
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub]]
    [clj-wamp.info.ids :refer [reverse-message-id message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.libs.channels :refer [errors]]
    [clj-wamp.libs.helpers :refer [finds-nested]]
    [taoensso.timbre :as log]
    ))

(defmulti handle-error (fn [_ data] (reverse-message-id (second data))))

(defmethod handle-error nil
  [_ data]
  (log/error "Error received from router" data)
  nil)

(defmethod handle-error :PUBLISH
  [_ data]
  "[ERROR, PUBLISH, PUBLISH.Request|id, Details|dict, Error|uri]"
  (log/error "Router failed to publish event" data)
  nil)

(defmethod handle-error :REGISTER
  [instance data]
  "[ERROR, REGISTER, REGISTER.Request|id, Details|dict, Error|uri]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 2)
                 error-msg (nth data 4)
                 [reg-uri reg-fn] (get pending req-id)]
             (log/error "Failed to register RPC method:" reg-uri "with error " error-msg)
             (if (= error-msg (error-uri :procedure-already-exists))
               [(dissoc unregistered reg-uri) registered (dissoc pending req-id)]
               [(assoc unregistered reg-uri reg-fn) registered (dissoc pending req-id)]
               )
             )))
  nil)


(defmethod handle-error :UNREGISTER
  [instance data]
  "[ERROR, REGISTER, REGISTER.Request|id, Details|dict, Error|uri]"
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (let [req-id (nth data 2)
                 error-msg (nth data 4)
                 [_ reg-uri] (get pending req-id)]
             (log/error "Failed to unregister RPC method:" reg-uri "with error " error-msg)
             (if (= error-msg (error-uri :no-such-registration))
               (if-let [[reg-id [_ _]] (finds-nested registered reg-uri)]
                 [unregistered (dissoc registered reg-id) (dissoc pending req-id)]
                 )
               [unregistered registered (dissoc pending req-id)]
               )
             )))
  nil)

(defmethod handle-error :CALL
  [_ data]
  "[ERROR, CALL, CALL.Request|id, Details|dict, Error|uri]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list]
   [ERROR, CALL, CALL.Request|id, Details|dict, Error|uri, Arguments|list, ArgumentsKw|dict]"
  (let [[_ _ req-id details error-uri arguments arguments-kw] data]
    (go (>! errors {:topic :CALL :req-id req-id :details details, :error-uri error-uri :arguments arguments, :arguments-kw arguments-kw}))

    (log/error "Callee was unable to process the call " error-uri))
  nil)

(defmethod handle-error :SUBSCRIBE
  [_ data]
  "[ERROR, SUBSCRIBE, SUBSCRIBE.Request|id, Details|dict, Error|uri]"
  (let [[_ _ req-id details error-uri] data]
  (log/error "Callee was unable to subscribe " error-uri)))


(defmethod handle-error :UNSUBSCRIBE
	[_ data]
	"[ERROR, UNSUBSCRIBE, UNSUBSCRIBE.Request|id, Details|dict,Error|uri]"
	(let [[_ _ req-id details error-uri] data]
		(log/error "Callee was unable to unsubscribe " error-uri)))
