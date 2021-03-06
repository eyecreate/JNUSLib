package de.mas.wiiu.jnus.implementations;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import de.mas.wiiu.jnus.NUSTitle;
import de.mas.wiiu.jnus.Settings;
import de.mas.wiiu.jnus.entities.TMD;
import de.mas.wiiu.jnus.entities.content.Content;
import de.mas.wiiu.jnus.utils.download.NUSDownloadService;
import lombok.Getter;

public class NUSDataProviderRemote extends NUSDataProvider {
    @Getter private final int version;
    @Getter private final long titleID;

    public NUSDataProviderRemote(NUSTitle title, int version, long titleID) {
        super(title);
        this.version = version;
        this.titleID = titleID;
    }

    @Override
    public InputStream getInputStreamFromContent(Content content, long fileOffsetBlock) throws IOException {
        NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();
        return downloadService.getInputStreamForURL(getRemoteURL(content), fileOffsetBlock);
    }

    private String getRemoteURL(Content content) {
        return String.format("%016x/%08X", getNUSTitle().getTMD().getTitleID(), content.getID());
    }

    @Override
    public byte[] getContentH3Hash(Content content) throws IOException {
        NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();
        String url = getRemoteURL(content) + ".h3";
        return downloadService.downloadToByteArray(url);
    }

    @Override
    public byte[] getRawTMD() throws IOException {
        NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();

        long titleID = getTitleID();
        int version = getVersion();

        return downloadService.downloadTMDToByteArray(titleID, version);
    }

    @Override
    public byte[] getRawTicket() throws IOException {
        NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();

        long titleID = getNUSTitle().getTMD().getTitleID();

        return downloadService.downloadTicketToByteArray(titleID);
    }

    @Override
    public byte[] getRawCert() throws IOException {
        NUSDownloadService downloadService = NUSDownloadService.getDefaultInstance();
        byte[] defaultCert = downloadService.downloadDefaultCertToByteArray();
        
        TMD tmd = getNUSTitle().getTMD();
        byte[] result = new byte[0];
        try{ 
            ByteArrayOutputStream fos = new ByteArrayOutputStream();
            fos.write(tmd.getCert1());
            fos.write(tmd.getCert2());
            fos.write(defaultCert);
            result = fos.toByteArray();
            fos.close();   
        }catch(Exception e){
            e.printStackTrace();
        }
        return result;
    }

    @Override
    public void cleanup() throws IOException {
        // We don't need this
    }
}
