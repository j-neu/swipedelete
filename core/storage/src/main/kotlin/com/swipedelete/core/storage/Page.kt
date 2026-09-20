package com.swipedelete.core.storage

/** A single page of results plus the token needed to fetch the next page. */
data class Page<T>(val items: List<T>, val next: PageToken?)

/** Opaque continuation token returned by [Page.next]. */
@JvmInline
value class PageToken(val value: String)
