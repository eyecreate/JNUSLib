package de.mas.wiiu.jnus.implementations.wud;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import de.mas.wiiu.jnus.implementations.wud.reader.WUDDiscReader;
import de.mas.wiiu.jnus.implementations.wud.reader.WUDDiscReaderCompressed;
import de.mas.wiiu.jnus.implementations.wud.reader.WUDDiscReaderSplitted;
import de.mas.wiiu.jnus.implementations.wud.reader.WUDDiscReaderUncompressed;
import de.mas.wiiu.jnus.utils.ByteUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

@Log
public class WUDImage {
    public static long WUD_FILESIZE = 0x5D3A00000L;

    @Getter private final File fileHandle;
    @Getter @Setter private WUDImageCompressedInfo compressedInfo = null;

    @Getter private final boolean isCompressed;
    @Getter private final boolean isSplitted;

    private long inputFileSize = 0L;
    @Getter private final WUDDiscReader WUDDiscReader;

    public WUDImage(File file) throws IOException {
        if (file == null || !file.exists()) {
            log.info("WUD file is null or does not exist");
            System.exit(1);
        }

        RandomAccessFile fileStream = new RandomAccessFile(file, "r");
        fileStream.seek(0);
        byte[] wuxheader = new byte[WUDImageCompressedInfo.WUX_HEADER_SIZE];
        fileStream.read(wuxheader);
        WUDImageCompressedInfo compressedInfo = new WUDImageCompressedInfo(wuxheader);

        if (compressedInfo.isWUX()) {
            log.info("Image is compressed");
            this.isCompressed = true;
            this.isSplitted = false;
            Map<Integer, Long> indexTable = new HashMap<>();
            long offsetIndexTable = compressedInfo.getOffsetIndexTable();
            fileStream.seek(offsetIndexTable);

            byte[] tableData = new byte[(int) (compressedInfo.getIndexTableEntryCount() * 0x04)];
            fileStream.read(tableData);
            int cur_offset = 0x00;
            for (long i = 0; i < compressedInfo.getIndexTableEntryCount(); i++) {
                indexTable.put((int) i, ByteUtils.getUnsingedIntFromBytes(tableData, (int) cur_offset, ByteOrder.LITTLE_ENDIAN));
                cur_offset += 0x04;
            }
            compressedInfo.setIndexTable(indexTable);
            setCompressedInfo(compressedInfo);
        } else {
            this.isCompressed = false;
            if (file.getName().equals(String.format(WUDDiscReaderSplitted.WUD_SPLITTED_DEFAULT_FILEPATTERN, 1))
                    && (file.length() == WUDDiscReaderSplitted.WUD_SPLITTED_FILE_SIZE)) {
                this.isSplitted = true;
                log.info("Image is splitted");
            } else {
                this.isSplitted = false;
            }
        }

        if (isCompressed()) {
            this.WUDDiscReader = new WUDDiscReaderCompressed(this);
        } else if (isSplitted()) {
            this.WUDDiscReader = new WUDDiscReaderSplitted(this);
        } else {
            this.WUDDiscReader = new WUDDiscReaderUncompressed(this);
        }

        fileStream.close();
        this.fileHandle = file;
    }

    public long getWUDFileSize() {
        if (inputFileSize == 0) {
            if (isSplitted()) {
                inputFileSize = calculateSplittedFileSize();
            } else if (isCompressed()) {
                inputFileSize = getCompressedInfo().getUncompressedSize();
            } else {
                inputFileSize = getFileHandle().length();
            }
        }
        return inputFileSize;
    }

    private long calculateSplittedFileSize() {
        long result = 0;
        File filehandlePart1 = getFileHandle();
        String pathToFiles = filehandlePart1.getParentFile().getAbsolutePath();
        for (int i = 1; i <= WUDDiscReaderSplitted.NUMBER_OF_FILES; i++) {
            String filePartPath = pathToFiles + File.separator + String.format(WUDDiscReaderSplitted.WUD_SPLITTED_DEFAULT_FILEPATTERN, i);
            File part = new File(filePartPath);
            if (part.exists()) {
                result += part.length();
            }
        }
        return result;
    }
}
