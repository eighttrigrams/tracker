(ns datastore
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [datastore.helpers :refer [un-namespace-keys]]
            [datastore.issues :as issues]
            [datastore.contexts :as contexts]))

;; entity types
;; - issues
;; - context
;; - context-issue-relation
;; TODO
;; think about groups
;; normal contexts are folders
;; groups are superfolders
;; directed acyclycal graph

;; TODO move to search ns, explore varags here, make tut about varargs and destructuring?
;; TODO in minimals, show examples which use substitution/formatting



(def update-issue issues/update-issue)

(def get-issue issues/get-issue)

(def update-issue-description issues/update-issue-description)

(def new-issue issues/new-issue)

(def reprioritize-issue issues/reprioritize-issue)

(def mark-issue-important issues/mark-issue-important) 

(def link-issue-contexts issues/link-issue-contexts)

(def delete-issue issues/delete-issue)

(def cycle-search-mode contexts/cycle-search-mode)

(def update-context-description contexts/update-context-description)

(def update-context contexts/update-context)

(def get-context contexts/get-context)

(def new-context contexts/new-context)
