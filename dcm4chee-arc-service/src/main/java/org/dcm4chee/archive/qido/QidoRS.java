/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */
package org.dcm4chee.archive.qido;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import javax.xml.transform.Templates;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.ElementDictionary;
import org.dcm4che.data.Sequence;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.data.VR;
import org.dcm4che.io.SAXTransformer;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Device;
import org.dcm4che.net.QueryOption;
import org.dcm4che.net.TransferCapability;
import org.dcm4che.net.TransferCapability.Role;
import org.dcm4che.net.service.QueryRetrieveLevel;
import org.dcm4che.util.StringUtils;
import org.dcm4chee.archive.Archive;
import org.dcm4chee.archive.common.IDWithIssuer;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.query.dao.QueryService;
import org.dcm4chee.archive.util.BeanLocator;
import org.dcm4chee.archive.util.query.Builder;
import org.dcm4chee.archive.wado.MediaTypes;
import org.jboss.resteasy.plugins.providers.multipart.MultipartRelatedOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.path.StringPath;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
@Path("/qido-rs/{AETitle}")
public class QidoRS {

    private static final int STATUS_OK = 200;
    private static final int STATUS_PARTIAL_CONTENT = 206;

    private static final Logger LOG = LoggerFactory.getLogger(QidoRS.class);

    private static ElementDictionary DICT =
            ElementDictionary.getStandardElementDictionary();

    private enum IncludeField {
        all,
        study(Tag.StudyDate, Tag.StudyTime, Tag.AccessionNumber,
                Tag.ModalitiesInStudy, Tag.ReferringPhysicianName,
                Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate,
                Tag.PatientSex, Tag.StudyID, Tag.StudyInstanceUID,
                Tag.NumberOfStudyRelatedSeries,
                Tag.NumberOfStudyRelatedInstances),
        series(Tag.Modality, Tag.SeriesDescription, Tag.SeriesNumber,
                Tag.SeriesInstanceUID, Tag.NumberOfSeriesRelatedInstances,
                Tag.PerformedProcedureStepStartDate,
                Tag.PerformedProcedureStepStartTime,
                Tag.RequestAttributesSequence),
        instance(Tag.SOPClassUID, Tag.SOPInstanceUID, Tag.InstanceNumber,
                Tag.Rows, Tag.Columns, Tag.BitsAllocated, Tag.NumberOfFrames);
        
        final int[] tags;

        IncludeField(int... tags) {
            this.tags = tags;
        }
    }

    private volatile static Templates jsonTpls;

    @Context
    private HttpServletRequest request;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpHeaders headers;

    @PathParam("AETitle")
    private String aet;

    @QueryParam("fuzzymatching")
    private boolean fuzzymatching;

    @QueryParam("datetimematching")
    private boolean datetimematching;

    @QueryParam("timezoneadjustment")
    private boolean timezoneadjustment;

    @QueryParam("offset")
    private int offset;

    @QueryParam("limit")
    private int limit;

    @QueryParam("includefield")
    private List<String> includefield;

    @QueryParam("orderby")
    private List<String> orderby;

    private OrderSpecifier<?>[] orderSpecifiers;

    private final Attributes keys = new Attributes(64);

    private boolean includeAll;

    private ApplicationEntity ae;

    private org.dcm4chee.archive.common.QueryParam queryParam;

    private QueryService queryService;

    private IDWithIssuer[] pids;

    private String name;

    @Override
    public String toString() {
        if (name == null) {
            if (request == null)
                return super.toString();

            name = request.getRemoteHost() + ':' + request.getRemotePort();
        }
        return name;
    }

    @GET
    @Path("/studies")
    @Produces("multipart/related")
    public Response searchForStudiesXML() {
        return search(QueryRetrieveLevel.STUDY, false, null, null,
                Output.DICOM_XML);
    }

    @GET
    @Path("/studies")
    @Produces("application/json")
    public Response searchForStudiesJSON() {
        return search(QueryRetrieveLevel.STUDY, false, null, null,
                Output.JSON);
    }

    @GET
    @Path("/series")
    @Produces("multipart/related")
    public Response searchForSeriesXML() {
        return search(QueryRetrieveLevel.SERIES, true, null, null,
                Output.DICOM_XML);
    }

    @GET
    @Path("/series")
    @Produces("application/json")
    public Response searchForSeriesJSON() {
        return search(QueryRetrieveLevel.SERIES, true, null, null,
                Output.JSON);
    }

    @GET
    @Path("/studies/{StudyInstanceUID}/series")
    @Produces("multipart/related")
    public Response searchForSeriesXML(
            @PathParam("StudyInstanceUID") String studyInstanceUID) {
        return search(QueryRetrieveLevel.SERIES, false, studyInstanceUID, null,
                Output.DICOM_XML);
    }

    @GET
    @Path("/studies/{StudyInstanceUID}/series")
    @Produces("application/json")
    public Response searchForSeriesJSON(
            @PathParam("StudyInstanceUID") String studyInstanceUID) {
        return search(QueryRetrieveLevel.SERIES, false, studyInstanceUID, null,
                Output.JSON);
    }

    @GET
    @Path("/instances")
    @Produces("multipart/related")
    public Response searchForInstancesXML() {
        return search(QueryRetrieveLevel.IMAGE, true, null, null,
                Output.DICOM_XML);
    }

    @GET
    @Path("/instances")
    @Produces("application/json")
    public Response searchForInstancesJSON() {
        return search(QueryRetrieveLevel.IMAGE, true, null, null,
                Output.JSON);
    }

    @GET
    @Path("/studies/{StudyInstanceUID}/series/{SeriesInstanceUID}/instances")
    @Produces("multipart/related")
    public Response searchForInstancesXML(
            @PathParam("StudyInstanceUID") String studyInstanceUID,
            @PathParam("SeriesInstanceUID") String seriesInstanceUID) {
        return search(QueryRetrieveLevel.IMAGE, false, 
                studyInstanceUID, seriesInstanceUID, Output.DICOM_XML);
    }

    @GET
    @Path("/studies/{StudyInstanceUID}/series/{SeriesInstanceUID}/instances")
    @Produces("application/json")
    public Response searchForInstancesJSON(
            @PathParam("StudyInstanceUID") String studyInstanceUID,
            @PathParam("SeriesInstanceUID") String seriesInstanceUID) {
        return search(QueryRetrieveLevel.IMAGE, false, 
                studyInstanceUID, seriesInstanceUID, Output.JSON);
    }

    private Response search(QueryRetrieveLevel qrlevel, boolean relational,
            String studyInstanceUID, String seriesInstanceUID, Output output) {
        init(qrlevel, relational, studyInstanceUID, seriesInstanceUID);
        try {
            queryService.createQuery(qrlevel, pids, keys, queryParam);
            int status = STATUS_OK;
            ArchiveAEExtension aeExt = ae.getAEExtension(ArchiveAEExtension.class);
            int maxResults = aeExt != null
                    ? aeExt.getQIDOMaxNumberOfResults()
                    : 0;
            int offset = Math.max(this.offset, 0);
            int limit = Math.max(this.limit, 0);
            if (maxResults > 0 && (limit == 0 || limit >  maxResults)) {
                int numResults = (int) (queryService.count() - offset);
                if (numResults == 0)
                    return Response.ok().build();
    
                if (numResults > maxResults) {
                    limit = maxResults;
                    status = STATUS_PARTIAL_CONTENT;
                }
            }
            if (offset > 0)
                queryService.offset(offset);
            
            if (limit > 0)
                queryService.limit(limit);
    
            if (orderSpecifiers != null)
                queryService.orderBy(orderSpecifiers);
    
            queryService.executeQuery();
            if (!queryService.hasMoreMatches())
                return Response.ok().build();
    
            return Response.status(status).entity(
                    output.entity(this, qrlevel)).build();
        } finally {
            cleanup();
        }
    }

    private void init(QueryRetrieveLevel qrlevel, boolean relational,
            String studyInstanceUID, String seriesInstanceUID) {
        List<MediaType> acceptableMediaTypes = headers.getAcceptableMediaTypes();
        LOG.info("{} >> QIDO-RS[{}?{}, Accept={}]", new Object[] {
                this,
                request.getRequestURL(),
                request.getQueryString(),
                acceptableMediaTypes});

        Device device = Archive.getInstance().getDevice();
        ae = device.getApplicationEntity(aet);
        if (ae == null || !ae.isInstalled())
            throw new WebApplicationException(Status.FORBIDDEN);

        TransferCapability tc = ae.getTransferCapabilityFor(
                    UID.StudyRootQueryRetrieveInformationModelFIND, Role.SCP);
        if (tc == null)
            throw new WebApplicationException(Status.FORBIDDEN);

        EnumSet<QueryOption> queryOpts = EnumSet.noneOf(QueryOption.class);
        if (relational)
            queryOpts.add(QueryOption.RELATIONAL);
        if (datetimematching)
            queryOpts.add(QueryOption.DATETIME);
        if (fuzzymatching)
            queryOpts.add(QueryOption.FUZZY);
        if (timezoneadjustment)
            queryOpts.add(QueryOption.TIMEZONE);

        if (!queryOpts.isEmpty()) {
            EnumSet<QueryOption> supportedQueryOpts = tc.getQueryOptions();
            if (supportedQueryOpts == null
                    || !supportedQueryOpts.containsAll(queryOpts))
                throw new WebApplicationException(Status.FORBIDDEN);
        }

        try {
            parseIncludefield();
    
            for (Map.Entry<String, List<String>> qParam
                    : uriInfo.getQueryParameters().entrySet()) {
                String name = qParam.getKey();
                if (isDicomAttribute(name))
                    parseDicomAttribute(name, qParam.getValue());
            }
            if (studyInstanceUID != null)
                keys.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUID);
            if (seriesInstanceUID != null)
                keys.setString(Tag.SeriesInstanceUID, VR.UI, seriesInstanceUID);

            parseOrderby(qrlevel);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e, Status.BAD_REQUEST);
        }

        queryService = BeanLocator.lookup(QueryService.class);
        queryParam = org.dcm4chee.archive.common.QueryParam.valueOf(
                ae, queryOpts, accessControlIDs());
        IDWithIssuer pid = IDWithIssuer.pidWithIssuer(keys,
                queryParam.getDefaultIssuerOfPatientID());
        this.pids = Archive.getInstance().pixQuery(ae, pid);
    }

    private static boolean isDicomAttribute(String name) {
        switch (name.charAt(0)) {
        case 'd':
            return !name.equals("datetimematching");
        case 'f':
            return !name.equals("fuzzymatching");
        case 'i':
            return !name.equals("includefield");
        case 'l':
            return !name.equals("limit");
        case 'o':
            return !name.equals("offset")
                && !name.equals("orderby");
        case 't':
            return !name.equals("timezoneadjustment");
        }
        return true;
    }

    private String[] accessControlIDs() {
        // TODO Auto-generated method stub
        return null;
    }

    private void parseIncludefield() {
        for (String s : includefield) {
            for (String field : StringUtils.split(s, ',')) {
                try {
                    IncludeField include = IncludeField.valueOf(field);
                    if (include == IncludeField.all) {
                        includeAll = true;
                        return;
                    }
                    for (int tag : include.tags)
                        keys.setNull(tag, DICT.vrOf(tag));
                } catch (IllegalArgumentException e) {
                    try {
                        int[] tagPath = parseTagPath(field);
                        int tag = tagPath[tagPath.length-1];
                        nestedKeys(tagPath).setNull(tag, DICT.vrOf(tag));
                    } catch (IllegalArgumentException e2) {
                        throw new IllegalArgumentException("includefield=" + s);
                    }
                }
            }
        }
    }

    private void parseOrderby(QueryRetrieveLevel qrLevel) {
        if (orderby.isEmpty())
            return;

        ArrayList<OrderSpecifier<?>> list = new ArrayList<OrderSpecifier<?>>();
        for (String s : orderby) {
            try {
                for (String field : StringUtils.split(s, ',')) {
                    boolean desc = field.charAt(0) == '-';
                    int tag = parseTag(desc ? field.substring(1) : field);
                    StringPath stringPath = Builder.stringPathOf(tag, qrLevel);
                    list.add(desc ? stringPath.desc() : stringPath.asc());
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("orderby=" + s);
            }
        }
        orderSpecifiers = list.toArray(new OrderSpecifier<?>[list.size()]);
    }

    private void parseDicomAttribute(String attrPath, List<String> values) {
        try {
            int[] tagPath = parseTagPath(attrPath);
            int tag = tagPath[tagPath.length-1];
            nestedKeys(tagPath).setString(tag, DICT.vrOf(tag),
                    values.toArray(new String[values.size()]));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(attrPath + "=" + values.get(0));
        } 
    }

    private Attributes nestedKeys(int[] tags) {
        Attributes item = keys;
        for (int i = 0; i < tags.length-1; i++) {
            int tag = tags[i];
            Sequence sq = item.getSequence(tag);
            if (sq == null)
                sq = item.newSequence(tag, 1);
            if (sq.isEmpty())
                sq.add(new Attributes());
            item = sq.get(0);
        }
        return item;
    }

    private static int[] parseTagPath(String attrPath) {
        return parseTagPath(StringUtils.split(attrPath, '.'));
    }

    private static int[] parseTagPath(String[] attrPath) {
        int[] tags = new int[attrPath.length];
        for (int i = 0; i < tags.length; i++)
            tags[i] = parseTag(attrPath[i]);
        return tags;
    }

    private static int parseTag(String tagOrKeyword) {
        try {
            return Integer.parseInt(tagOrKeyword, 16);
        } catch (IllegalArgumentException e) {
            int tag = DICT.tagForKeyword(tagOrKeyword);
            if (tag == -1)
                throw new IllegalArgumentException(tagOrKeyword);
            return tag;
        }
    }

    private void cleanup() {
        if (queryService != null)
            queryService.close();
    }

    private enum Output {
        DICOM_XML {
            @Override
            Object entity(QidoRS service, QueryRetrieveLevel qrlevel) {
                return service.writeXML(qrlevel);
            }
        },
        JSON {
            @Override
            Object entity(QidoRS service, QueryRetrieveLevel qrlevel) {
                return service.writeJSON(qrlevel);
            }
        };
        
        abstract Object entity(QidoRS service, QueryRetrieveLevel qrlevel);
    }

    private Object writeXML(QueryRetrieveLevel qrlevel) {
        MultipartRelatedOutput output = new MultipartRelatedOutput();
        int count = 0;
        while (queryService.hasMoreMatches()) {
            final int partNumber = ++count;
            final Attributes match = filter(addRetrieveURI(queryService.nextMatch(), qrlevel));
            output.addPart(new StreamingOutput() {

                @Override
                public void write(OutputStream out) throws IOException,
                        WebApplicationException {
                    LOG.info("{} << {}:QIDO-RS[Content-Type=application/dicom+xml]",
                            QidoRS.this, partNumber);
                    try {
                        SAXTransformer.getSAXWriter(new StreamResult(out)).write(match);
                    } catch (Exception e) {
                        throw new WebApplicationException(e);
                    }
                }},
                MediaTypes.APPLICATION_DICOM_XML_TYPE);
        }
        return output;
    }

    private Object writeJSON(QueryRetrieveLevel qrlevel) {
        final ArrayList<Attributes> matches = new ArrayList<Attributes>();
        while (queryService.hasMoreMatches()) {
            matches.add(filter(addRetrieveURI(queryService.nextMatch(), qrlevel)));
        }
        StreamingOutput output = new StreamingOutput(){

            @Override
            public void write(OutputStream out) throws IOException {
                for (int i = 0, n=matches.size(); i < n; i++) {
                    out.write(i == 0 ? '[' : ',');
                    LOG.info("{} << {}:QIDO-RS[Content-Type=application/json]",
                            QidoRS.this, i+1);
                     try {
                        SAXTransformer.getSAXWriter(jsonTpls(), new StreamResult(out))
                            .write(matches.get(i));
                    } catch (Exception e) {
                        throw new WebApplicationException(e);
                    }
                }
                out.write(']');
            }
        };
        return output;
    }

    private static Templates jsonTpls() throws Exception {
        Templates jsonTpls0 = jsonTpls;
        if (jsonTpls == null)
            jsonTpls = jsonTpls0 = SAXTransformer.newTemplates(new StreamSource(
                    QidoRS.class.getResource("json_compact.xsl").toString()));
        return jsonTpls0;
    }

    private Attributes addRetrieveURI(Attributes match, QueryRetrieveLevel qrlevel) {
        match.setString(Tag.RetrieveURI, VR.UT, retrieveURI(match, qrlevel));
        return match;
    }

    private String retrieveURI(Attributes match, QueryRetrieveLevel qrlevel) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(uriInfo.getBaseUri())
          .append("wado-rs/")
          .append(aet)
          .append("/studies/")
          .append(match.getString(Tag.StudyInstanceUID));

        if (qrlevel == QueryRetrieveLevel.STUDY)
            return sb.toString();

        sb.append("/series/")
          .append(match.getString(Tag.SeriesInstanceUID));

        if (qrlevel == QueryRetrieveLevel.SERIES)
            return sb.toString();

        sb.append("/instances/")
          .append(match.getString(Tag.SOPInstanceUID));
        return sb.toString();
    }

    private Attributes filter(Attributes match) {
        if (includeAll)
            return match;

        Attributes filtered = new Attributes(match.size());
        filtered.addSelected(match, Tag.SpecificCharacterSet,
                Tag.RetrieveAETitle, Tag.InstanceAvailability, Tag.RetrieveURI);
        filtered.addSelected(match, keys);
        return filtered;
    }

}
