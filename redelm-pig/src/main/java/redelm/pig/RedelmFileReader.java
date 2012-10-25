/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package redelm.pig;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.log4j.Logger;

public class RedelmFileReader {
  private static final Logger LOG = Logger.getLogger(RedelmFileReader.class);

  public static final Footer readFooter(FSDataInputStream f, long l) throws IOException {
    long footerIndexIndex = l - 8 - 8;
    LOG.info("reading footer index at " + footerIndexIndex);
    f.seek(footerIndexIndex);
    long footerIndex = f.readLong();
    byte[] magic = new byte[8];
    f.readFully(magic);
    if (!Arrays.equals(RedelmFileWriter.MAGIC, magic)) {
      throw new RuntimeException("Not a Red Elm file");
    }
    LOG.info("read footer index: " + footerIndex);
    f.seek(footerIndex);
    int version = f.readInt();
    if (version != RedelmFileWriter.CURRENT_VERSION) {
      throw new RuntimeException(
          "unsupported version: " + version + ". " +
          "supporting up to " + RedelmFileWriter.CURRENT_VERSION);
    }
//    Footer footer = Footer.fromJSON(f.readUTF());
    try {
      return (Footer) new ObjectInputStream(f).readObject();
    } catch (ClassNotFoundException e) {
      throw new IOException("Can not deserialize footer", e);
    }
  }

  private final List<BlockMetaData> blocks;
  private final FSDataInputStream f;
  private int currentBlock = 0;
  private Set<String> paths = new HashSet<String>();
  private long previousReadIndex = 0;

  public RedelmFileReader(FSDataInputStream f, List<BlockMetaData> blocks, List<String[]> colums) {
    this.f = f;
    this.blocks = blocks;
    for (String[] path : colums) {
      paths.add(Arrays.toString(path));
    }
  }

  public BlockData readColumns() throws IOException {
    if (currentBlock == blocks.size()) {
      return null;
    }
    List<ColumnData> result = new ArrayList<ColumnData>();
    BlockMetaData block = blocks.get(currentBlock);
    for (ColumnMetaData mc : block.getColumns()) {
      String pathKey = Arrays.toString(mc.getPath());
      if (paths.contains(pathKey)) {
        byte[] repetitionLevels = read(pathKey + ".r", mc.getRepetitionStart(), mc.getRepetitionLength());
        byte[] definitionLevels = read(pathKey + ".d", mc.getDefinitionStart(), mc.getDefinitionLength());
        byte[] data = read(pathKey + ".data", mc.getDataStart(), mc.getDataLength());
        result.add(new ColumnData(mc.getPath(), repetitionLevels, definitionLevels, data));
      }
    }

    ++currentBlock;
    return new BlockData(block.getRecordCount(), result);
  }

  private byte[] read(String name, long start, long length) throws IOException {
    byte[] data = new byte[(int)length];
    if (start != previousReadIndex) {
      LOG.info("seeking to next column " + (start - previousReadIndex) + " bytes");
    }
    long t0 = System.currentTimeMillis();
    f.readFully(start, data);
    long t1 = System.currentTimeMillis();
    LOG.info("Read " + length + " bytes for column " + name + " in " + (t1 - t0) + " ms: " + (float)(t1 - t0)/data.length + " ms/byte");
    previousReadIndex = start + length;
    return data;
  }

}