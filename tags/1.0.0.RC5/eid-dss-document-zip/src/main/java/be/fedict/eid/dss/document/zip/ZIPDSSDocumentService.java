/*
 * eID Digital Signature Service Project.
 * Copyright (C) 2010 FedICT.
 * Copyright (C) 2011 Frank Cornelis.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package be.fedict.eid.dss.document.zip;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.utils.resolver.ResourceResolverSpi;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import be.fedict.eid.applet.service.signer.DigestAlgo;
import be.fedict.eid.applet.service.signer.KeyInfoKeySelector;
import be.fedict.eid.applet.service.signer.SignatureFacet;
import be.fedict.eid.applet.service.signer.facets.RevocationDataService;
import be.fedict.eid.applet.service.signer.odf.ODFUtil;
import be.fedict.eid.applet.service.signer.time.TimeStampService;
import be.fedict.eid.applet.service.signer.time.TimeStampServiceValidator;
import be.fedict.eid.applet.service.spi.IdentityDTO;
import be.fedict.eid.applet.service.spi.SignatureServiceEx;
import be.fedict.eid.dss.spi.DSSDocumentContext;
import be.fedict.eid.dss.spi.DSSDocumentService;
import be.fedict.eid.dss.spi.DocumentVisualization;
import be.fedict.eid.dss.spi.SignatureInfo;
import be.fedict.eid.dss.spi.utils.XAdESValidation;

public class ZIPDSSDocumentService implements DSSDocumentService {

	private static final long serialVersionUID = 1L;

	private static final Log LOG = LogFactory
			.getLog(ZIPDSSDocumentService.class);

	private DSSDocumentContext documentContext;

	static {
		/*
		 * Initialize the Apache XML Security library, else we get an NPE on
		 * Transforms.addTransform.
		 */
		Init.init();
	}

	public void init(DSSDocumentContext context, String contentType)
			throws Exception {
		this.documentContext = context;
	}

	public void checkIncomingDocument(byte[] document) throws Exception {
	}

	public DocumentVisualization visualizeDocument(byte[] document,
			String language) throws Exception {

		ZipInputStream zipInputStream = new ZipInputStream(
				new ByteArrayInputStream(document));
		ZipEntry zipEntry;
		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer.append("<html>");
		stringBuffer.append("<head>");
		stringBuffer.append("<title>ZIP package</title>");
		stringBuffer.append("</head>");
		stringBuffer.append("<body>");
		stringBuffer.append("<h1>ZIP package</h1>");
		while (null != (zipEntry = zipInputStream.getNextEntry())) {
			if (ODFUtil.isSignatureFile(zipEntry)) {
				continue;
			}
			String zipEntryName = zipEntry.getName();
			stringBuffer.append("<p>" + zipEntryName + "</p>");
		}
		stringBuffer.append("</body></html>");

		return new DocumentVisualization("text/html", stringBuffer.toString()
				.getBytes());
	}

	public SignatureServiceEx getSignatureService(
			InputStream documentInputStream, TimeStampService timeStampService,
			TimeStampServiceValidator timeStampServiceValidator,
			RevocationDataService revocationDataService,
			SignatureFacet signatureFacet, OutputStream documentOutputStream,
			String role, IdentityDTO identity, byte[] photo,
			DigestAlgo signatureDigestAlgo) throws Exception {

		return new ZIPSignatureService(documentInputStream, signatureFacet,
				documentOutputStream, revocationDataService, timeStampService,
				role, identity, photo, signatureDigestAlgo);
	}

	@Override
	public List<SignatureInfo> verifySignatures(byte[] document,
			byte[] originalDocument) throws Exception {
		ZipInputStream zipInputStream = new ZipInputStream(
				new ByteArrayInputStream(document));
		ZipEntry zipEntry;
		while (null != (zipEntry = zipInputStream.getNextEntry())) {
			if (ODFUtil.isSignatureFile(zipEntry)) {
				break;
			}
		}
		List<SignatureInfo> signatureInfos = new LinkedList<SignatureInfo>();
		if (null == zipEntry) {
			return signatureInfos;
		}
		XAdESValidation xadesValidation = new XAdESValidation(
				this.documentContext);
		Document documentSignaturesDocument = ODFUtil
				.loadDocument(zipInputStream);
		NodeList signatureNodeList = documentSignaturesDocument
				.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
		for (int idx = 0; idx < signatureNodeList.getLength(); idx++) {
			Element signatureElement = (Element) signatureNodeList.item(idx);
			KeyInfoKeySelector keySelector = new KeyInfoKeySelector();
			DOMValidateContext domValidateContext = new DOMValidateContext(
					keySelector, signatureElement);
			ZIPURIDereferencer dereferencer = new ZIPURIDereferencer(document);
			domValidateContext.setURIDereferencer(dereferencer);

			XMLSignatureFactory xmlSignatureFactory = XMLSignatureFactory
					.getInstance();
			XMLSignature xmlSignature = xmlSignatureFactory
					.unmarshalXMLSignature(domValidateContext);
			boolean valid = xmlSignature.validate(domValidateContext);
			if (!valid) {
				continue;
			}

			// check whether all files have been signed properly
			SignedInfo signedInfo = xmlSignature.getSignedInfo();
			@SuppressWarnings("unchecked")
			List<Reference> references = signedInfo.getReferences();
			Set<String> referenceUris = new HashSet<String>();
			for (Reference reference : references) {
				referenceUris.add(reference.getURI());
			}
			zipInputStream = new ZipInputStream(new ByteArrayInputStream(
					document));
			while (null != (zipEntry = zipInputStream.getNextEntry())) {
				if (ODFUtil.isSignatureFile(zipEntry)) {
					continue;
				}
				if (false == referenceUris.contains(zipEntry.getName())) {
					LOG.warn("no ds:Reference for ZIP entry: "
							+ zipEntry.getName());
					return signatureInfos;
				}
			}

			if (null != originalDocument) {
				for (Reference reference : references) {
					if (null != reference.getType()) {
						/*
						 * We skip XAdES and eID identity ds:Reference.
						 */
						continue;
					}
					String digestAlgo = reference.getDigestMethod()
							.getAlgorithm();
					LOG.debug("ds:Reference digest algo: " + digestAlgo);
					String referenceUri = reference.getURI();
					LOG.debug("ds:Reference URI: " + referenceUri);
					byte[] digestValue = reference.getDigestValue();

					org.apache.xml.security.signature.XMLSignature xmldsig = new org.apache.xml.security.signature.XMLSignature(
							documentSignaturesDocument,
							org.apache.xml.security.signature.XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA512,
							Canonicalizer.ALGO_ID_C14N_EXCL_WITH_COMMENTS);
					xmldsig.addDocument(referenceUri, null, digestAlgo);
					ResourceResolverSpi zipResourceResolver = new ZIPResourceResolver(
							originalDocument);
					xmldsig.addResourceResolver(zipResourceResolver);
					org.apache.xml.security.signature.SignedInfo apacheSignedInfo = xmldsig
							.getSignedInfo();
					org.apache.xml.security.signature.Reference apacheReference = apacheSignedInfo
							.item(0);
					apacheReference.generateDigestValue();
					byte[] originalDigestValue = apacheReference
							.getDigestValue();
					if (false == Arrays
							.equals(originalDigestValue, digestValue)) {
						throw new RuntimeException("not original document");
					}
				}
				/*
				 * So we already checked whether no files were changed, and that
				 * no files were added compared to the original document. Still
				 * have to check whether no files were removed.
				 */
				ZipInputStream originalZipInputStream = new ZipInputStream(
						new ByteArrayInputStream(originalDocument));
				ZipEntry originalZipEntry;
				Set<String> referencedEntryNames = new HashSet<String>();
				for (Reference reference : references) {
					if (null != reference.getType()) {
						continue;
					}
					referencedEntryNames.add(reference.getURI());
				}
				while (null != (originalZipEntry = originalZipInputStream
						.getNextEntry())) {
					if (ODFUtil.isSignatureFile(originalZipEntry)) {
						continue;
					}
					if (false == referencedEntryNames.contains(originalZipEntry
							.getName())) {
						LOG.warn("missing ds:Reference for ZIP entry: "
								+ originalZipEntry.getName());
						throw new RuntimeException(
								"missing ds:Reference for ZIP entry: "
										+ originalZipEntry.getName());
					}
				}
			}

			X509Certificate signer = keySelector.getCertificate();
			SignatureInfo signatureInfo = xadesValidation.validate(
					documentSignaturesDocument, xmlSignature, signatureElement,
					signer);
			signatureInfos.add(signatureInfo);
		}
		return signatureInfos;
	}
}