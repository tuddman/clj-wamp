(ns clj-wamp.client.callee
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub]]
    [clj-wamp.core :as core]
    [clj-wamp.client.v2 :refer [send! error exception-message]]
    [clj-wamp.libs.helpers :as lib]
    [clj-wamp.info.ids :refer [message-id]]
    [clj-wamp.info.uris :refer [error-uri]]
    [clj-wamp.client.handler.error :refer [handle-error]]
    ))


(defn yield
  "[YIELD, INVOCATION.Request|id, Options|dict]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list]
   [YIELD, INVOCATION.Request|id, Options|dict, Arguments|list, ArgumentsKw|dict]"
  [instance request-id options seq-args kw-args]
  (let [args [(message-id :YIELD) request-id options]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
    (send! instance args)))


(declare yield-progressive)

(defn- yield-return
  [instance req-id return]
  (if-let [return-error (:error return)]
    (let [return-error-uri (if (:uri return-error) (:uri return-error) (error-uri :application-error))]
      (error instance (message-id :INVOCATION) req-id (dissoc return-error :uri) return-error-uri))
    (cond
      (:result return) (yield instance req-id (merge {} (:options return)) [(:result return)] nil)
      (:seq-result return) (yield instance req-id (merge {} (:options return)) (vec (:seq-result return)) nil)
      (:map-result return) (yield instance req-id (merge {} (:options return)) [] (:map-result return))
      (:chan-result return) (yield-progressive instance req-id (:chan-result return))
      :else (yield instance req-id {} [return] nil))))

(defn- yield-progressive
  [instance req-id return-chan]
  (go-loop []
    (when-let [return (<! return-chan)]
      (yield-return instance req-id return)
      (recur))))

(defn perform-invocation
  [instance req-id rpc-fn options seq-args map-args]
  (try
    (let [return (rpc-fn {:call-id req-id
                          :options options
                          :seq-args seq-args
                          :map-args map-args})]
      (yield-return instance req-id return))
    (catch Throwable e
      (error instance (message-id :INVOCATION) req-id (exception-message instance e) (error-uri :internal-error)))))


(defn register
  "[REGISTER, Request|id, Options|dict, Procedure|uri]"
  [instance request-id options uri]
  (send! instance [(message-id :REGISTER) request-id options uri]))

(defn unregister
  "[UNREGISTER, Request|id, REGISTERED.Registration|id]"
  [instance request-id reg-uri]
  (send! instance [(message-id :UNREGISTER) request-id reg-uri]))


(defn register-next!
  [instance]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (if-let [[reg-uri reg-fn] (first unregistered)]
             (let [req-id (core/new-rand-id)]
               (register instance req-id {} reg-uri)
               [(dissoc unregistered reg-uri) registered (assoc pending req-id [reg-uri reg-fn])])
             [unregistered registered pending]))))

(defn register-new!
  "Associates the uri to the function as unregistred command. If the uri is already registred,
  it returns the existing map"
  [instance reg-uri reg-fn]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending :as registrations]]
           (if-not (lib/contains-nested? (lib/map->vec registrations) #(= % reg-uri))
             [(assoc unregistered reg-uri reg-fn) registered pending]
             [unregistered registered pending])
           ))
  (register-next! instance)
  )

(defn unregister!
  "Deassociates the procedure with the id and sends the unregister message to the server"
  [instance reg-uri]
  (swap! (:registrations instance)
         (fn [[unregistered registered pending]]
           (if-let [[reg-id [_ _]] (lib/finds-nested registered reg-uri)]
             (let [req-id (core/new-rand-id)]
               (unregister instance req-id reg-id)
               [unregistered registered (assoc pending req-id [reg-id reg-uri])])
             [unregistered registered pending])
           )))
