package de.mas.wiiu.jnus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;

import de.mas.wiiu.jnus.entities.TMD;
import de.mas.wiiu.jnus.entities.Ticket;
import de.mas.wiiu.jnus.entities.content.Content;
import de.mas.wiiu.jnus.entities.fst.FSTEntry;
import de.mas.wiiu.jnus.implementations.NUSDataProvider;
import de.mas.wiiu.jnus.utils.CheckSumWrongException;
import de.mas.wiiu.jnus.utils.HashUtil;
import de.mas.wiiu.jnus.utils.StreamUtils;
import de.mas.wiiu.jnus.utils.Utils;
import de.mas.wiiu.jnus.utils.cryptography.NUSDecryption;
import lombok.Getter;
import lombok.extern.java.Log;

@Log
public final class DecryptionService {
    private static Map<NUSTitle, DecryptionService> instances = new HashMap<>();
    @Getter private final NUSTitle NUSTitle;

    public static DecryptionService getInstance(NUSTitle nustitle) {
        if (!instances.containsKey(nustitle)) {
            instances.put(nustitle, new DecryptionService(nustitle));
        }
        return instances.get(nustitle);
    }

    private DecryptionService(NUSTitle nustitle) {
        this.NUSTitle = nustitle;
    }

    public Ticket getTicket() {
        return getNUSTitle().getTicket();
    }

    public void decryptFSTEntryTo(boolean useFullPath, FSTEntry entry, String outputPath, boolean skipExistingFile) throws IOException, CheckSumWrongException {
        if (entry.isNotInPackage() || entry.getContent() == null) {
            return;
        }
        
        //log.info("Decrypting " + entry.getFilename());

        String targetFilePath = new StringBuilder().append(outputPath).append("/").append(entry.getFilename()).toString();
        String fullPath = new StringBuilder().append(outputPath).toString();

        if (useFullPath) {
            targetFilePath = new StringBuilder().append(outputPath).append(entry.getFullPath()).toString();
            fullPath = new StringBuilder().append(outputPath).append(entry.getPath()).toString();
            if (entry.isDir()) { // If the entry is a directory. Create it and return.
                Utils.createDir(targetFilePath);
                return;
            }
        } else if (entry.isDir()) {
            return;
        }

        if (!Utils.createDir(fullPath)) {
            return;
        }

        File target = new File(targetFilePath);

        if (skipExistingFile) {
            File targetFile = new File(targetFilePath);
            if (targetFile.exists()) {
                if (entry.isDir()) {
                    return;
                }
                if (targetFile.length() == entry.getFileSize()) {
                    Content c = entry.getContent();
                    if (c.isHashed()) {
                        log.info("File already exists: " + entry.getFilename());
                        return;
                    } else {
                        if (Arrays.equals(HashUtil.hashSHA1(target, (int) c.getDecryptedFileSize()), c.getSHA2Hash())) {
                            log.info("File already exists: " + entry.getFilename());
                            return;
                        } else {
                            log.info("File already exists with the same filesize, but the hash doesn't match: " + entry.getFilename());
                        }
                    }

                } else {
                    log.info("File already exists but the filesize doesn't match: " + entry.getFilename());
                }
            }
        }

        FileOutputStream outputStream = new FileOutputStream(new File(targetFilePath));
        try {
            decryptFSTEntryToStream(entry, outputStream);
        } catch (CheckSumWrongException e) {
            if (entry.getFilename().endsWith(".xml") && Utils.checkXML(new File(targetFilePath))) {
                log.info("Hash doesn't match, but it's an XML file and it looks okay.");
            } else {
                log.info("Hash doesn't match!");
                throw e;
            }
        }
    }

    public void decryptFSTEntryToStream(FSTEntry entry, OutputStream outputStream) throws IOException, CheckSumWrongException {
        if (entry.isNotInPackage() || entry.getContent() == null) {
            return;
        }

        Content c = entry.getContent();

        long fileSize = entry.getFileSize();
        long fileOffset = entry.getFileOffset();
        long fileOffsetBlock = entry.getFileOffsetBlock();

        NUSDataProvider dataProvider = getNUSTitle().getDataProvider();

        InputStream in = dataProvider.getInputStreamFromContent(c, fileOffsetBlock);

        try {
            decryptFSTEntryFromStreams(in, outputStream, fileSize, fileOffset, c);
        } catch (CheckSumWrongException e) {
            log.info("Hash doesn't match");
            if (entry.getFilename().endsWith(".xml")) {
                if (outputStream instanceof PipedOutputStream) {
                    log.info("Hash doesn't match. Please check the data for " + entry.getFullPath());
                } else {
                    throw e;
                }
            } else if (entry.getContent().isUNKNWNFlag1Set()) {
                log.info("But file is optional. Don't worry.");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("Detailed info:").append(System.lineSeparator());
                sb.append(entry).append(System.lineSeparator());
                sb.append(entry.getContent()).append(System.lineSeparator());
                sb.append(String.format("%016x", this.NUSTitle.getTMD().getTitleID()));
                sb.append(e.getMessage() + " Calculated Hash: " + Utils.ByteArrayToString(e.getGivenHash()) + ", expected hash: "
                        + Utils.ByteArrayToString(e.getExpectedHash()));
                log.info(sb.toString());
                throw e;
            }
        }
    }

    private void decryptFSTEntryFromStreams(InputStream inputStream, OutputStream outputStream, long filesize, long fileoffset, Content content)
            throws IOException, CheckSumWrongException {
        decryptStreams(inputStream, outputStream, filesize, fileoffset, content);
    }

    private void decryptContentFromStream(InputStream inputStream, OutputStream outputStream, Content content) throws IOException, CheckSumWrongException {
        long filesize = content.getDecryptedFileSize();
        log.info("Decrypting Content " + String.format("%08X", content.getID()));
        decryptStreams(inputStream, outputStream, filesize, 0L, content);
    }

    private void decryptStreams(InputStream inputStream, OutputStream outputStream, long size, long offset, Content content)
            throws IOException, CheckSumWrongException {
        NUSDecryption nusdecryption = new NUSDecryption(getTicket());
        short contentIndex = (short) content.getIndex();

        long encryptedFileSize = content.getEncryptedFileSize();

        if (content.isEncrypted()) {
            if (content.isHashed()) {
                NUSDataProvider dataProvider = getNUSTitle().getDataProvider();
                byte[] h3 = dataProvider.getContentH3Hash(content);
                nusdecryption.decryptFileStreamHashed(inputStream, outputStream, size, offset, (short) contentIndex, h3);
            } else {
                nusdecryption.decryptFileStream(inputStream, outputStream, size, (short) contentIndex, content.getSHA2Hash(), encryptedFileSize);
            }
        } else {
            StreamUtils.saveInputStreamToOutputStreamWithHash(inputStream, outputStream, size, content.getSHA2Hash(), encryptedFileSize);
        }

        inputStream.close();
        outputStream.close();
    }

    public void decryptContentTo(Content content, String outPath, boolean skipExistingFile) throws IOException, CheckSumWrongException {
        String targetFilePath = outPath + File.separator + content.getFilenameDecrypted();
        if (skipExistingFile) {
            File targetFile = new File(targetFilePath);
            if (targetFile.exists()) {
                if (targetFile.length() == content.getDecryptedFileSize()) {
                    log.info("File already exists : " + content.getFilenameDecrypted());
                    return;
                } else {
                    log.info("File already exists but the filesize doesn't match: " + content.getFilenameDecrypted());
                }
            }
        }

        if (!Utils.createDir(outPath)) {
            return;
        }

        log.info("Decrypting Content " + String.format("%08X", content.getID()));

        FileOutputStream outputStream = new FileOutputStream(new File(targetFilePath));

        decryptContentToStream(content, outputStream);
    }

    public void decryptContentToStream(Content content, OutputStream outputStream) throws IOException, CheckSumWrongException {
        if (content == null) {
            return;
        }

        NUSDataProvider dataProvider = getNUSTitle().getDataProvider();
        InputStream inputStream = dataProvider.getInputStreamFromContent(content, 0);

        decryptContentFromStream(inputStream, outputStream, content);
    }

    public PipedInputStreamWithException getDecryptedOutputAsInputStream(FSTEntry fstEntry) throws IOException {
        PipedInputStreamWithException in = new PipedInputStreamWithException();
        PipedOutputStream out = new PipedOutputStream(in);

        new Thread(() -> {
            try { // Throwing it in both cases is EXTREMLY important. Otherwise it'll end in a deadlock
                decryptFSTEntryToStream(fstEntry, out);
                in.throwException(null);
            } catch (Exception e) {
                in.throwException(e);
            }

        }).start();

        return in;
    }

    public PipedInputStreamWithException getDecryptedContentAsInputStream(Content content) throws IOException, CheckSumWrongException {
        PipedInputStreamWithException in = new PipedInputStreamWithException();
        PipedOutputStream out = new PipedOutputStream(in);

        new Thread(() -> {
            try {// Throwing it in both cases is EXTREMLY important. Otherwise it'll end in a deadlock
                decryptContentToStream(content, out);
                in.throwException(null);
            } catch (Exception e) {
                in.throwException(e);
            }
        }).start();

        return in;
    }

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // Decrypt FSTEntry to OutputStream
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    public void decryptFSTEntryTo(String entryFullPath, OutputStream outputStream) throws IOException, CheckSumWrongException {
        FSTEntry entry = getNUSTitle().getFSTEntryByFullPath(entryFullPath);
        if (entry == null) {
            log.info("File not found");
        }

        decryptFSTEntryToStream(entry, outputStream);
    }

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // Decrypt single FSTEntry to File
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    public void decryptFSTEntryTo(String entryFullPath, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(false, entryFullPath, outputFolder);
    }

    public void decryptFSTEntryTo(boolean fullPath, String entryFullPath, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(fullPath, entryFullPath, outputFolder, getNUSTitle().isSkipExistingFiles());
    }

    public void decryptFSTEntryTo(String entryFullPath, String outputFolder, boolean skipExistingFiles) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(false, entryFullPath, outputFolder, getNUSTitle().isSkipExistingFiles());
    }

    public void decryptFSTEntryTo(boolean fullPath, String entryFullPath, String outputFolder, boolean skipExistingFiles)
            throws IOException, CheckSumWrongException {
        FSTEntry entry = getNUSTitle().getFSTEntryByFullPath(entryFullPath);
        if (entry == null) {
            log.info("File not found");
            return;
        }

        decryptFSTEntryTo(fullPath, entry, outputFolder, skipExistingFiles);
    }

    public void decryptFSTEntryTo(FSTEntry entry, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(false, entry, outputFolder);
    }

    public void decryptFSTEntryTo(boolean fullPath, FSTEntry entry, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(fullPath, entry, outputFolder, getNUSTitle().isSkipExistingFiles());
    }

    public void decryptFSTEntryTo(FSTEntry entry, String outputFolder, boolean skipExistingFiles) throws IOException, CheckSumWrongException {
        decryptFSTEntryTo(false, entry, outputFolder, getNUSTitle().isSkipExistingFiles());
    }

    /*
     * public void decryptFSTEntryTo(boolean fullPath, FSTEntry entry,String outputFolder, boolean skipExistingFiles) throws IOException{
     * decryptFSTEntry(fullPath,entry,outputFolder,skipExistingFiles); }
     */

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // Decrypt list of FSTEntry to Files
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    public void decryptAllFSTEntriesTo(String outputFolder) throws IOException, CheckSumWrongException {
        Utils.createDir(outputFolder + File.separator + "code");
        Utils.createDir(outputFolder + File.separator + "content");
        Utils.createDir(outputFolder + File.separator + "meta");
        decryptFSTEntriesTo(true, ".*", outputFolder);
    }

    public void decryptFSTEntriesTo(String regEx, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntriesTo(true, regEx, outputFolder);
    }

    public void decryptFSTEntriesTo(boolean fullPath, String regEx, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryListTo(fullPath, getNUSTitle().getFSTEntriesByRegEx(regEx), outputFolder);
    }

    public void decryptFSTEntryListTo(List<FSTEntry> list, String outputFolder) throws IOException, CheckSumWrongException {
        decryptFSTEntryListTo(true, list, outputFolder);
    }

    public void decryptFSTEntryListTo(boolean fullPath, List<FSTEntry> list, String outputFolder) throws IOException, CheckSumWrongException {
        for (FSTEntry entry : list) {
            decryptFSTEntryTo(fullPath, entry, outputFolder, getNUSTitle().isSkipExistingFiles());
        }
    }

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // Save decrypted contents
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    public void decryptPlainContentByID(int ID, String outputFolder) throws IOException, CheckSumWrongException {
        decryptPlainContent(getTMDFromNUSTitle().getContentByID(ID), outputFolder);
    }

    public void decryptPlainContentByIndex(int index, String outputFolder) throws IOException, CheckSumWrongException {
        decryptPlainContent(getTMDFromNUSTitle().getContentByIndex(index), outputFolder);
    }

    public void decryptPlainContent(Content c, String outputFolder) throws IOException, CheckSumWrongException {
        decryptPlainContents(new ArrayList<Content>(Arrays.asList(c)), outputFolder);
    }

    public void decryptPlainContents(List<Content> list, String outputFolder) throws IOException, CheckSumWrongException {
        for (Content c : list) {
          decryptContentTo(c, outputFolder, getNUSTitle().isSkipExistingFiles());
        }
    }

    public void decryptAllPlainContents(String outputFolder) throws IOException, CheckSumWrongException {
        decryptPlainContents(new ArrayList<Content>(getTMDFromNUSTitle().getAllContents().values()), outputFolder);
    }

    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    // Other
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!
    private TMD getTMDFromNUSTitle() {
        return getNUSTitle().getTMD();
    }
}
