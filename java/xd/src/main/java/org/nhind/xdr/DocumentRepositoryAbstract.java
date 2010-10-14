/* 
 * Copyright (c) 2010, NHIN Direct Project
 * All rights reserved.
 *  
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright 
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright 
 *    notice, this list of conditions and the following disclaimer in the 
 *    documentation and/or other materials provided with the distribution.  
 * 3. Neither the name of the the NHIN Direct Project (nhindirect.org)
 *    nor the names of its contributors may be used to endorse or promote products 
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY 
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY 
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND 
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.nhind.xdr;

import ihe.iti.xds_b._2007.DocumentRepositoryPortType;
import ihe.iti.xds_b._2007.DocumentRepositoryService;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import ihe.iti.xds_b._2007.RetrieveDocumentSetRequestType;
import ihe.iti.xds_b._2007.RetrieveDocumentSetResponseType;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType.Document;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.util.ByteArrayDataSource;
import javax.naming.InitialContext;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.soap.MTOMFeature;
import javax.xml.ws.soap.SOAPBinding;

import oasis.names.tc.ebxml_regrep.xsd.rs._3.RegistryResponseType;

import org.apache.commons.lang.StringUtils;
import org.nhind.xdm.MailClient;
import org.nhind.xdm.impl.SmtpMailClient;
import org.nhindirect.xd.common.DirectDocument;
import org.nhindirect.xd.common.DirectMessage;
import org.nhindirect.xd.routing.RoutingResolver;
import org.nhindirect.xd.routing.impl.RoutingResolverImpl;

/**
 * Base class for handling incoming XDR requests.
 * 
 * @author Vince
 */
public abstract class DocumentRepositoryAbstract {

    protected WebServiceContext mywscontext;
    
    protected String endpoint = null;
    protected String messageId = null;
    protected String relatesTo = null;
    protected String action = null;
    protected String to = null;
    
    private String thisHost = null;
    private String remoteHost = null;
    private String pid = null;
    private String from = null;
    private String suffix = null;
    private String replyEmail = null;
    
    private RoutingResolver resolver = new RoutingResolverImpl();

    private static final Logger LOGGER = Logger.getLogger(DocumentRepositoryAbstract.class.getPackage().getName());

    /**
     * Handle an incoming XDR request with a
     * ProvideAndRegisterDocumentSetRequestType object.
     * 
     * @param body
     *            The ProvideAndRegisterDocumentSetRequestType object
     *            representing an XDR message
     * @return a RegistryResponseType object
     */
    public abstract RegistryResponseType documentRepositoryProvideAndRegisterDocumentSetB(ProvideAndRegisterDocumentSetRequestType body);
    
    /**
     * Handle an incoming XDR request with a RetrieveDocumentSetRequestType
     * object
     * 
     * @param body
     *            The RetrieveDocumentSetRequestType object representing an XDR
     *            message
     * @return a RetrieveDocumentSetRequestType object
     */
    public abstract RetrieveDocumentSetResponseType documentRepositoryRetrieveDocumentSet(RetrieveDocumentSetRequestType body);
    
    /**
     * Handle an incoming ProvideAndRegisterDocumentSetRequestType object and
     * transform to XDM or relay to another XDR endponit.
     * 
     * @param prdst
     *            The incoming ProvideAndRegisterDocumentSetRequestType object
     * @return a RegistryResponseType object
     * @throws Exception
     */
    protected RegistryResponseType provideAndRegisterDocumentSet(ProvideAndRegisterDocumentSetRequestType prdst) throws Exception {
        RegistryResponseType resp = null;
        try {
            getHeaderData();
            
            @SuppressWarnings("unused")
            InitialContext ctx = new InitialContext();

            // Get metadata
            DirectDocument.Metadata metadata = new DirectDocument.Metadata();
            metadata.setValues(prdst.getSubmitObjectsRequest());

            // Get endpoints
            List<String> forwards = new ArrayList<String>();
            for (String recipient : metadata.getSs_intendedRecipient())
            {
                String address = StringUtils.remove(recipient, "|");
                forwards.add(StringUtils.splitPreserveAllTokens(address, "^")[0]);
            }
            
            messageId = UUID.randomUUID().toString();

            // Send to SMTP endpoints
            if (resolver.hasSmtpEndpoints(forwards))
            {
                // Get a reply address
                replyEmail = metadata.getAuthorPerson();
                replyEmail = StringUtils.splitPreserveAllTokens(replyEmail, "^")[0];
                replyEmail = StringUtils.contains(replyEmail, "@") ? replyEmail : "nhindirect@nhindirect.org";

                // Get a suffix
                suffix = StringUtils.contains(metadata.getMimeType(), "pdf") ? "pdf" : "xml"; // FIXME
                
                // Get document
                byte[] docs = getDocs(prdst);

                LOGGER
                        .info("SENDING EMAIL TO " + resolver.getSmtpEndpoints(forwards) + " with message id "
                                + messageId);

                // Construct message wrapper
                DirectMessage message = new DirectMessage(replyEmail, resolver.getSmtpEndpoints(forwards));
                message.setSubject("data");
                message.setBody("data attached");
                
                // Construct document wrapper
                DirectDocument document = new DirectDocument();
                document.setData(new String(docs));
                document.getMetadata().setValues(prdst.getSubmitObjectsRequest());
                
                // Add document to message
                message.addDocument(document);
                
                // Send mail
                MailClient mailClient = new SmtpMailClient();
                mailClient.mail(message, messageId, suffix);
            }

            // Send to XD endpoints
            for (String reqEndpoint : resolver.getXdEndpoints(forwards))
            {
                String to = StringUtils.remove(reqEndpoint, "?wsdl");

                Long threadId = new Long(Thread.currentThread().getId());
                LOGGER.info("THREAD ID " + threadId);
                ThreadData threadData = new ThreadData(threadId);
                threadData.setTo(to);

                List<Document> docs = prdst.getDocument();
                Document oldDoc = docs.get(0);
                docs.clear();
                Document doc = new Document();
                doc.setId(oldDoc.getId());

                DataHandler dh = oldDoc.getValue();
                ByteArrayOutputStream buffOS = new ByteArrayOutputStream();
                dh.writeTo(buffOS);
                byte[] buff = buffOS.toByteArray();

                DataSource source = new ByteArrayDataSource(buff, "application/xml; charset=UTF-8");
                DataHandler dhnew = new DataHandler(source);
                doc.setValue(dhnew);

                docs.add(doc);

                LOGGER.info(" SENDING TO ENDPOINT " + to);
                DocumentRepositoryService service = new DocumentRepositoryService();
                service.setHandlerResolver(new RepositoryHandlerResolver());

                DocumentRepositoryPortType port = service.getDocumentRepositoryPortSoap12(new MTOMFeature(true, 1));

                BindingProvider bp = (BindingProvider) port;
                SOAPBinding binding = (SOAPBinding) bp.getBinding();
                binding.setMTOMEnabled(true);

                bp.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, reqEndpoint);

                RegistryResponseType rrt = port.documentRepositoryProvideAndRegisterDocumentSetB(prdst);
                String test = rrt.getStatus();
                if (test.indexOf("Failure") >= 0) {
                    throw new Exception("Failure Returned from XDR forward");
                }
            }
            
            resp = getRepositoryProvideResponse(messageId);

            relatesTo = messageId;
            action = "urn:ihe:iti:2007:ProvideAndRegisterDocumentSet-bResponse";
            to = endpoint;
            
            setHeaderData();
        } catch (Exception e) {
            e.printStackTrace();
            throw (e);
        }

        return resp;
    }

    /**
     * Create a RegistryResponseType object.
     * 
     * @param messageId
     *            The message ID
     * @return a RegistryResponseType object
     * @throws Exception
     */
    protected RegistryResponseType getRepositoryProvideResponse(String messageId) throws Exception {
        RegistryResponseType rrt = null;
        try { // Call Web Service Operation

            String status = "urn:oasis:names:tc:ebxml-regrep:ResponseStatusType:Success";  // TODO initialize WS operation arguments here

            try {

                rrt = new RegistryResponseType();
                rrt.setStatus(status);


            } catch (Exception ex) {
                LOGGER.info("not sure what this ");
                ex.printStackTrace();
            }


        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return rrt;
    }

    /**
     * Extract the documents from the XDR message.
     * 
     * @param prdst
     *            The XDR message
     * @return the raw documents from the XDR message
     */
    private byte[] getDocs(ProvideAndRegisterDocumentSetRequestType prdst) {
        List<Document> documents = prdst.getDocument();

        byte[] ret = null;
        try {
            for (ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType.Document doc : documents) {
                DataHandler dh = doc.getValue();
                ByteArrayOutputStream buffOS = new ByteArrayOutputStream();
                dh.writeTo(buffOS);
                ret = buffOS.toByteArray();

            }
        } catch (Exception x) {
            x.printStackTrace();
        }
        return ret;
    }

    /**
     * Extract header values from a ThreadData object.
     */
    protected void getHeaderData() {
        Long threadId = new Long(Thread.currentThread().getId());
        LOGGER.info("DTHREAD ID " + threadId);
        
        ThreadData threadData = new ThreadData(threadId);
        this.endpoint = threadData.getReplyAddress();
        this.messageId = threadData.getMessageId();
        this.to = threadData.getTo();
        this.thisHost = threadData.getThisHost();
        this.remoteHost = threadData.getRemoteHost();
        this.pid = threadData.getPid();
        this.action = threadData.getAction();
        this.from = threadData.getFrom();
        
        LOGGER.info(threadData.toString());
    }

    /**
     * Build a ThreadData object with header information.
     */
    protected void setHeaderData() {
        Long threadId = new Long(Thread.currentThread().getId());
        LOGGER.info("THREAD ID " + threadId);
        
        ThreadData threadData = new ThreadData(threadId);
        threadData.setTo(this.to);
        threadData.setMessageId(this.messageId);
        threadData.setRelatesTo(this.relatesTo);
        threadData.setAction(this.action);
        threadData.setThisHost(this.thisHost);
        threadData.setRemoteHost(this.remoteHost);
        threadData.setPid(this.pid);
        threadData.setFrom(this.from);
        
        LOGGER.info(threadData.toString());
    }
}
