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
package org.dcm4chee.archive.wado;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;

import org.dcm4che.data.Attributes;
import org.dcm4che.data.UID;
import org.dcm4che.imageio.codec.Decompressor;
import org.dcm4che.io.DicomInputStream;
import org.dcm4che.io.DicomInputStream.IncludeBulkData;
import org.dcm4che.io.DicomOutputStream;
import org.dcm4che.util.SafeClose;
import org.dcm4chee.archive.entity.InstanceFileRef;
import org.slf4j.Logger;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
class DicomObjectOutput implements StreamingOutput {

    private final InstanceFileRef fileRef;
    private final Attributes attrs;
    private final String tsuid;
    private final MediaType mediaType;
    private final HttpServletRequest request;
    private final Logger log;

    DicomObjectOutput(InstanceFileRef fileRef, Attributes attrs, String tsuid,
            MediaType mediaType, HttpServletRequest request, Logger log) {
        this.fileRef = fileRef;
        this.attrs = attrs;
        this.tsuid = tsuid;
        this.mediaType = mediaType;
        this.request = request;
        this.log = log;
    }

    public void write(OutputStream out) throws IOException,
            WebApplicationException {
        log.info("{}@{} << {}: Content-Type={}, iuid={}",
                new Object[] {
                    request.getRemoteUser(),
                    request.getRemoteHost(),
                    System.identityHashCode(request),
                    mediaType,
                    fileRef.sopInstanceUID});
        DicomInputStream dis = new DicomInputStream(fileRef.getFile());
        try {
            dis.setIncludeBulkData(IncludeBulkData.URI);
            Attributes dataset = dis.readDataset(-1, -1);
            dataset.addAll(attrs);
            if (tsuid != fileRef.transferSyntaxUID) {
                Decompressor.decompress(dataset, fileRef.transferSyntaxUID);
            }
            Attributes fmi = dataset.createFileMetaInformation(tsuid);
            @SuppressWarnings("resource")
            DicomOutputStream dos =
                new DicomOutputStream(out, UID.ExplicitVRLittleEndian);
            dos.writeDataset(fmi, dataset);
        } finally {
            SafeClose.close(dis);
        }
    }

}
