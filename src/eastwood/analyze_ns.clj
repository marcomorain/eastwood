(ns eastwood.analyze-ns
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.string :as string]
            [clojure.pprint :as pp]
            [eastwood.util :as util]
            [eastwood.passes :refer [propagate-def-name]]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [eastwood.copieddeps.dep10.clojure.tools.reader :as tr]
            [eastwood.copieddeps.dep10.clojure.tools.reader.reader-types :as rts]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.jvm :as ana.jvm]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.emit-form :refer [emit-form]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.ast :refer [postwalk prewalk cycling]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer :as ana :refer [analyze] :rename {analyze -analyze}]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.utils :as utils]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.env :as env]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.source-info :refer [source-info]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.cleanup :refer [cleanup]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.elide-meta :refer [elide-meta]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.constant-lifter :refer [constant-lift]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.warn-earmuff :refer [warn-earmuff]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.add-binding-atom :refer [add-binding-atom]]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.box :refer [box]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.annotate-branch :refer [annotate-branch]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.annotate-methods :refer [annotate-methods]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.annotate-class-id :refer [annotate-class-id]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.annotate-internal-name :refer [annotate-internal-name]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.fix-case-test :refer [fix-case-test]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.classify-invoke :refer [classify-invoke]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.validate :refer [validate]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.infer-tag :refer [infer-tag ensure-tag]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.annotate-tag :refer [annotate-tag]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.validate-loop-locals :refer [validate-loop-locals]]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.passes.jvm.analyze-host-expr :refer [analyze-host-expr]]))

;; munge-ns, uri-for-ns, pb-reader-for-ns were copied from library
;; jvm.tools.analyzer, then later probably diverged from each other.

(defn ^:private munge-ns [ns-sym]
  (-> (name ns-sym)
      (string/replace "." "/")
      (string/replace "-" "_")
      (str ".clj")))

(defn uri-for-ns
  "Returns a URI representing the namespace. Throws an
  exception if URI not found."
  [ns-sym]
  (let [source-path (munge-ns ns-sym)
        uri (io/resource source-path)]
    (when-not uri
      (throw (Exception. (str "No file found for namespace " ns-sym))))
    uri))

(defn pb-reader-for-ns
  "Returns an IndexingReader for namespace ns-sym"
  [ns-sym]
  (let [uri (uri-for-ns ns-sym)]
    (rts/indexing-push-back-reader (java.io.PushbackReader. (io/reader uri))
                                   1 (munge-ns ns-sym))))

(defn all-ns-names-set []
  (set (map str (all-ns))))

(defn gen-interface-form? [form]
  (and (sequential? form)
       (contains? #{'gen-interface 'clojure.core/gen-interface}
                  (first form))))

;; Avoid macroexpand'ing a gen-interface form more than once, since it
;; causes an exception to be thrown.
(defn dont-expand-twice? [form]
  (gen-interface-form? form))

(defn begin-file-debug [filename ns opt]
  (when (:record-forms? opt)
    (binding [*out* (:forms-read-wrtr opt)]
      (println (format "\n\n== Analyzing file '%s'\n" filename)))
    (binding [*out* (:forms-emitted-wrtr opt)]
      (println (format "\n\n== Analyzing file '%s'\n" filename)))))

;; run-passes is a cut-down version of run-passes in
;; tools.analyzer.jvm.  It eliminates phases that are not needed for
;; linting, and which can cause analysis to fail for code that we
;; would prefer to give linter warnings for, rather than throw an
;; exception.

(defn run-passes
  [ast]
  (-> ast

    uniquify-locals
    add-binding-atom

    (prewalk (fn [ast]
               (-> ast
                 warn-earmuff
                 source-info
                 elide-meta
                 annotate-methods
                 fix-case-test
                 annotate-class-id
                 annotate-internal-name
                 propagate-def-name)))  ;; custom pass added for Eastwood

    ((fn analyze [ast]
       (postwalk ast
                 (fn [ast]
                   (-> ast
                     annotate-tag
                     analyze-host-expr
                     infer-tag
                     validate
                     classify-invoke
                     ;; constant-lift pass is not helpful for Eastwood
                     ;;constant-lift ;; needs to be run after validate so that :maybe-class is turned into a :const
                     (validate-loop-locals analyze))))))

    ;; The following passes are intentionally far fewer than the ones
    ;; in t.a.j/run-passes.
    (prewalk (comp cleanup
                ensure-tag
                box))))

(defn remaining-forms [pushback-reader forms]
  (let [eof (reify)]
    (loop [forms forms]
      (let [form (tr/read pushback-reader nil eof)]
        (if (identical? form eof)
          forms
          (recur (conj forms form)))))))

(defn analyze-file
  "Takes a file path and optionally a pushback reader.  Returns a map
  with at least the following keys:

  :forms - a sequence of forms as read in, with any forms within a
      top-level do, or do forms nested within a top-level do,
      'flattened' to the top level themselves.  This sequence will
      include all forms in the file, as far as the file could be
      successfully read, even if an exception was thrown earlier than
      that during analysis or evaluation of the forms.

  :asts - a sequence of ASTs of the forms that were successfully
      analyzed without exception.  They correspond one-to-one with
      forms in the :forms sequence.

  :exception - nil if no exception occurred.  An Exception object if
      an exception was thrown during analysis, emit-form, or eval.

  If :exception is not nil, then the following keys will also be part
  of the returned map:

  :exception-phase - If an exception was thrown, this is a keyword
      indicating in what portion of analyze-file's operation this
      exception occurred.  Currently always :analyze+eval

  :exception-form - If an exception was thrown, the current form being
      processed when the exception occurred.

  Options:
  - :reader  a pushback reader to use to read the namespace forms
  - :opt     a map of analyzer options
    - :debug A set of keywords.
      - :all Enable all of the following debug messages.
      - :progress Print simple progress messages as analysis proceeds.
      - :ns Print all namespaces that exist according to (all-ns)
            before analysis begins, and then only when that set of
            namespaces changes after each form is analyzed.

  eg. (analyze-file \"my/ns.clj\" :opt {:debug-all true})"
  [source-path & {:keys [reader opt]}]
  (let [debug-ns (or (contains? (:debug opt) :ns)
                     (contains? (:debug opt) :all))
        eof (reify)]
    (when debug-ns
      (println (format "all-ns before (analyze-file \"%s\") begins:"
                       source-path))
      (pp/pprint (sort (all-ns-names-set))))
    ;; If we eval a form that changes *ns*, I want it to go back to
    ;; the original before returning.
    (binding [*ns* *ns*
              *file* (str source-path)]
      (env/with-env (ana.jvm/global-env)
        (begin-file-debug *file* *ns* opt)
        (loop [forms []
               asts []]
          (let [form (tr/read reader nil eof)]
            (if (identical? form eof)
              {:forms forms, :asts asts, :exception nil}
              (let [[exc ast]
                    (try
                      (binding [ana.jvm/run-passes run-passes]
                        [nil (ana.jvm/analyze+eval form)])
                      (catch Exception e
                        [e nil]))]
                (if exc
                  {:forms (remaining-forms reader (conj forms form)),
                   :asts asts, :exception exc, :exception-phase :analyze+eval,
                   :exception-form form}
                  (recur (conj forms form)
                         (conj asts ast)))))))))))


(defn analyze-ns
  "Takes an IndexingReader and a namespace symbol.
  Returns a map of results of analyzing the namespace.  The map
  contains these keys:

  :analyze-results - The value associated with this key is itself a
      map with the following keys:
      :namespace - The source-nsym argument to this fn
      :source - A string containing the source read in from the
          namespace's source file.
      :forms - See analyze-file docs for details
      :asts - See analyze-file
  :exception, :exception-phase, :exception-form - See analyze-file

  Options:
  - :reader  a pushback reader to use to read the namespace forms
  - :opt     a map of analyzer options
    - same as analyze-file.  See there.

  eg. (analyze-ns 'my-ns :opt {} :reader (pb-reader-for-ns 'my.ns))"
  [source-nsym & {:keys [reader opt] :or {reader (pb-reader-for-ns source-nsym)}}]
  (let [source-path (munge-ns source-nsym)
        {:keys [analyze-results] :as m}
        (analyze-file source-path :reader reader :opt opt)]
    (assoc (dissoc m :forms :asts)
      :analyze-results {:source (slurp (io/resource source-path))
                        :namespace source-nsym
                        :forms (:forms m)
                        :asts (:asts m)})))
