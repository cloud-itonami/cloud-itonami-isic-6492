(ns credit.edge.kotobase-http
  "A `js/fetch`-based `:http-fn` for `langchain.kotoba-db/kotoba-api-
  async` (kotobase-persistence-migration, docs/adr/0003). Direct mirror
  of `commitledger.edge.kotobase-http` (sibling actor, same fleet) --
  see that ns's docstring for the full reasoning (a Cloudflare Pages
  Function has no synchronous I/O primitive at all, so this targets
  `kotoba-api-async` rather than the original synchronous `kotoba-api`)."
  (:require [clojure.string :as str]
            [credit.edge.pcompat :as pc]))

#?(:cljs
   (defn fetch-http-fn
     [{:keys [url method headers body]}]
     (-> (js/fetch url
                   #js {:method (str/upper-case (name (or method :post)))
                        :headers (clj->js (or headers {}))
                        :body body})
         (.then (fn [resp]
                  (.then (.text resp)
                         (fn [text] {:status (.-status resp) :body text}))))
         (.catch (fn [e]
                   {:status 599 :body (str "kotobase_http fetch failed: " (ex-message e))})))))

(defn resolved-mock-http-fn
  "A NON-fetch `:http-fn` for tests/tooling -- see `commitledger.edge.
  kotobase-http/resolved-mock-http-fn`'s own docstring."
  [respond-fn]
  (fn [req] (pc/resolved (respond-fn req))))
