// Document parsers.
//
// DocumentParser interface, content-type router, Tika and PDFBox impls.
// Add a new parser by implementing DocumentParser and declaring which MIME
// types it supports. ParserRouter falls back to Tika when nothing claims
// the content type.
package io.pinoRAG.ingest.parse;
