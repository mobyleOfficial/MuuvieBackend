package org.mobyle.domain.model

/** A file uploaded for an import, as received: [name] is the client's file name. Never stored. */
class ImportFile(val name: String, val bytes: ByteArray)
