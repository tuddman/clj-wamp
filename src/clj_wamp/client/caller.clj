(ns clj-wamp.client.caller
  (:require
    [clojure.core.async
     :refer [>! <! >!! <!! go go-loop chan buffer close! thread
             alts! alts!! timeout pub]]
    [clj-wamp.client.node :refer [send!]]
    [clj-wamp.info.ids :refer [message-id]]))


(defn call
  "[CALL, Request|id, Options|dict, Procedure|uri]
   [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list]
   [CALL, Request|id, Options|dict, Procedure|uri, Arguments|list,ArgumentsKw|dict]

   'Request' is a random, ephemeral ID chosen by the Caller and
   used to correlate the Dealer's response with the request.

      o  'Options' is a dictionary that allows to provide additional call
   request details in an extensible way.  This is described further
   below.

      o  'Procedure' is the URI of the procedure to be called.

      o  'Arguments' is a list of positional call arguments (each of
   arbitrary type).  The list may be of zero length.

      o  'ArgumentsKw' is a dictionary of keyword call arguments (each of
   arbitrary type).  The dictionary may be empty."

  [instance request-id options uri seq-args kw-args]
  (let [args [(message-id :CALL) request-id options uri]
        args (if (some? seq-args) (conj args seq-args) args)
        args (if (some? kw-args) (conj args kw-args) args)]
    (send! instance args)))

