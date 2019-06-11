// TODO see below
/*
 * Module: r2-lcp-kotlin
 * Developers: Aferdita Muriqi
 *
 * Copyright (c) 2019. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.lcp.license

import android.content.Context
import com.github.kittinunf.fuel.Fuel
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.then
import org.joda.time.DateTime
import org.readium.lcp.sdk.Lcp
import org.readium.r2.lcp.license.model.LicenseDocument
import org.readium.r2.lcp.license.model.StatusDocument
import org.readium.r2.lcp.public.*
import org.readium.r2.lcp.service.DeviceService
import org.readium.r2.lcp.service.LicensesRepository
import org.readium.r2.lcp.service.NetworkService
import org.readium.r2.shared.promise
import org.zeroturnaround.zip.ZipUtil
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.*


class License(private var documents: ValidatedDocuments,
              private val validation: LicenseValidation,
              private val licenses: LicensesRepository,
              private val device: DeviceService,
              private val network: NetworkService):LCPLicense {

    override val license: LicenseDocument
        get() = documents.license
    override val status: StatusDocument?
        get() = documents.status
     override val encryptionProfile: String?
        get() = license.encryption.profile

    override fun decipher(data: ByteArray) : ByteArray? {
        val context = documents.getContext()
        return Lcp().decrypt(context, data)
    }

    override val charactersToCopyLeft: Int?
        get() {
            try {
                val charactersLeft = licenses.copiesLeft(license.id)
                if (charactersLeft != null) {
                    return charactersLeft
                }
            } catch (error:Error) {
                Timber.e(error)
            }
            return null
        }

    override  val canCopy: Boolean
        get() = (charactersToCopyLeft ?: 1) > 0

    override fun copy(text: String) : String? {
        var charactersLeft = charactersToCopyLeft ?: return text
        if (charactersLeft <= 0) {
            return null
        }
        var result = text
        if (result.length > charactersLeft) {
            result = result.substring(0, charactersLeft)
        }
        try {
            charactersLeft = maxOf(0, charactersLeft - result.length)
            licenses.setCopiesLeft(charactersLeft, license.id)
        } catch (error:Error) {
            Timber.e(error)
        }
        return result
    }
    override val pagesToPrintLeft: Int?
        get() {
            try {
                val pagesLeft = licenses.printsLeft(license.id)
                if (pagesLeft != null) {
                    return pagesLeft
                }
            } catch (error:Error) {
                Timber.e(error)
            }
            return null
        }
    override val canPrint: Boolean
        get() = (pagesToPrintLeft ?: 1) > 0

    override fun print(pagesCount: Int) : Boolean {
        var pagesLeft = pagesToPrintLeft ?: return true
        if (pagesLeft < pagesCount) {
            return false
        }
        try {
            pagesLeft = maxOf(0, pagesLeft - pagesCount)
            licenses.setPrintsLeft(pagesLeft, license.id)
        } catch (error:Error) {
            Timber.e(error)
        }
        return true
    }

    override val canRenewLoan: Boolean
        get() = status?.link(StatusDocument.Rel.renew) != null

    override val maxRenewDate: DateTime?
        get() = status?.potentialRights?.end

    // TODO needs to be tested
    override fun renewLoan(end: DateTime?, present: URLPresenter, completion: (LCPError?) -> Unit) {

        fun callPUT(url: URL, callback: (ByteArray) -> Unit)  {
            this.network.fetch(url.toString(), method = NetworkService.Method.put) { status, data ->
                when (status) {
                    200 -> callback(data)
                    400 -> throw RenewError.renewFailed
                    403 -> throw RenewError.invalidRenewalPeriod(maxRenewDate = this.maxRenewDate)
                    else -> throw RenewError.unexpectedServerError
                }
            }
        }

        fun callHTML(url: URL, callback: (ByteArray) -> Unit)  {
            val statusURL = try { this.license.url(LicenseDocument.Rel.status) } catch (e: Throwable) { null } ?: throw LCPError.licenseInteractionNotAvailable
                present(url) {
                    this.network.fetch(statusURL.toString()) { status, data  ->
                        if (status != 200) {
                            throw LCPError.network(null)
                        }
                        callback(data)
                    }
                }
        }

        val params = this.device.asQueryParameters
        end?.let {
            params.add(Pair("end", end))
        }
        val status = this.documents.status
        val link = status?.link(StatusDocument.Rel.renew)
        val url = link?.url(params)
        if (status == null || link == null || url == null) {
            throw LCPError.licenseInteractionNotAvailable
        }
        if (link.type == "text/html") {
            callHTML(url) {
                validateStatusDocument(it)
            }
        } else {
            callPUT(url) {
                validateStatusDocument(it)
            }
        }

    }
    override val canReturnPublication: Boolean
        get() = status?.link(StatusDocument.Rel.`return`) != null

    override fun returnPublication(completion: (LCPError?) -> Unit) {
        val status = this.documents.status
        val url = try { status?.url(StatusDocument.Rel.`return`, device.asQueryParameters) } catch (e: Throwable) { null }
        if (status == null || url == null) {
            completion(LCPError.licenseInteractionNotAvailable)
            return
        }
        network.fetch(url.toString(), method = NetworkService.Method.put) { statusCode, data  ->
            when (statusCode) {
                200 -> validateStatusDocument(data)
                400 -> throw ReturnError.returnFailed
                403 -> throw ReturnError.alreadyReturnedOrExpired
                else -> throw ReturnError.unexpectedServerError
            }
            completion(null)
        }
    }

    init {
        LicenseValidation.observe(validation) { documents, error  ->
            if (documents != null) {
                this.documents = documents
            }
        }
    }

    fun moveLicense(archivePath: String, licenseData: ByteArray) {
        val pathInZip = "META-INF/license.lcpl"
        Timber.i("LCP moveLicense")
        val source = File(archivePath)
        val tmpZip = File("$archivePath.tmp")
        tmpZip.delete()
        source.copyTo(tmpZip)
        source.delete()
        if (ZipUtil.containsEntry(tmpZip, pathInZip)) {
            ZipUtil.removeEntry(tmpZip, pathInZip)
        }
        ZipUtil.addEntry(tmpZip, pathInZip,  licenseData,  source)
        tmpZip.delete()
    }

    fun fetchPublication(context: Context, parameters: List<Pair<String, Any?>>? = null): Promise<String, Exception> {
        val license = this.documents.license
        val title = license.link(LicenseDocument.Rel.publication)?.title
        val url = license.url(LicenseDocument.Rel.publication)

        val rootDir:String = context.getExternalFilesDir(null)?.path + "/"
        val fileName = UUID.randomUUID().toString()
        return Fuel.download(url.toString()).destination { _, _ ->
            Timber.i("LCP destination %s%s", rootDir, fileName)
            File(rootDir, fileName)

        }.promise() then {
            val (_, response, _) = it
            Timber.i( "LCP destination %s%s", rootDir , fileName)
            Timber.i("LCP then  %s", response.url.toString())

            rootDir + fileName

        }
    }

    private fun validateStatusDocument(data: ByteArray) : Unit =
            validation.validate(LicenseValidation.Document.status(data)) { validatedDocuments: ValidatedDocuments?, error: Error? -> }


}

