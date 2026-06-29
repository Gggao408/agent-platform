package com.agent.rag;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本切分器 — 将长文档切分为固定大小的块。
 * <p>
 * 使用固定长度 + 重叠滑动窗口策略：
 * <ul>
 *   <li>chunkSize = 512 字符（可配置）</li>
 *   <li>overlap = 128 字符（可配置，保证语义连续性）</li>
 * </ul>
 *
 * <h3>你需要实现的内容：</h3>
 * {@link #split(String)} — 核心切分逻辑
 *
 * <h3>切分策略演进路线（v2 可做）：</h3>
 * <ol>
 *   <li>v1: 固定长度 + 重叠</li>
 *   <li>v2: 按段落边界智能切分（避免在句子中间切断）</li>
 *   <li>v3: 语义切分（用 embedding 相似度突变检测边界）</li>
 * </ol>
 */
@Slf4j
public class ChunkSplitter {

    /** 每个块的最大字符数 */
    private final int chunkSize;

    /** 相邻块之间的重叠字符数 */
    private final int overlap;

    /** 段落分隔符 */
    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\n\n|\r\n\r\n");

    public ChunkSplitter() {
        this(512, 128);
    }

    public ChunkSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 切块结果。
     */
    public record Chunk(
            int index,           // 块序号（从0开始）
            String text,         // 块文本
            int startChar,       // 在原文中的起始字符位置
            int endChar          // 在原文中的结束字符位置
    ) {}

    /**
     * TODO: 将文本切分为重叠的块。
     *
     * <h3>算法：</h3>
     * <pre>
     * 原文: "ABCDEFGHIJKLMNOPQRSTUVWXYZ"  (26字符)
     * chunkSize=10, overlap=3
     *
     * chunk_0: "ABCDEFGHIJ"     (0-10)
     * chunk_1: "HIJKLMNOPQR"    (7-17, 与chunk_0重叠 "HIJ")
     * chunk_2: "OPQRSTUVWX"     (14-24, 与chunk_1重叠 "OPQ")
     * chunk_3: "VWXYZ"          (21-26, 不足10字符)
     * </pre>
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>遍历文本，步长 = chunkSize - overlap</li>
     *   <li>每次取 [start, start+chunkSize) 区间</li>
     *   <li>末尾不足一块的也保留（不丢弃）</li>
     *   <li>如果单块文本已经小于等于 chunkSize，直接返回单块</li>
     * </ol>
     *
     * <h3>进阶优化（v2）：</h3>
     * 在切分时尝试对齐段落边界：
     * <pre>{@code
     * // 找到 end 位置前后最近的段落分隔符，优先在那里切断
     * int breakPoint = findNearestParagraphBreak(text, end);
     * }</pre>
     *
     * @param text 完整文档文本
     * @return 切分后的块列表
     */
    public List<Chunk> split(String text) {
        List<Chunk> chunks = new ArrayList<>();
        int step = chunkSize - overlap;
        int index = 0;
        for(int start = 0; start<text.length();start+=step){
            int end = Math.min(start+chunkSize,text.length());
            String chunkText = text.substring(start,end);
            chunks.add(new Chunk(index,chunkText,start,end));
            index++;
        }
        return chunks;
    }

    /**
     * 按段落初步切分（辅助方法）。
     * <p>
     * 在固定长度切分之前，先按段落（\n\n）拆分，
     * 超长的段落再进行固定长度切分。这样能保持段落的语义完整性。
     */
    public List<Chunk> splitByParagraph(String text) {
       List<Chunk> chunks = new ArrayList<>();
       int index= 0;
       String[] paragraphs= PARAGRAPH_SEP.split(text);
       StringBuilder buffer = new StringBuilder();
       int bufferStart = 0;
       for(String para: paragraphs){
        if(para.isBlank()){
            continue;
        }
        if(para.length()>chunkSize){
            if(!buffer.isEmpty()){
                chunks.add(new Chunk(index++,buffer.toString(),bufferStart,
            bufferStart+buffer.length()));
            buffer.setLength(0);
            }
           for(Chunk sub :split(para)){
            chunks.add(new Chunk(index++, sub.text(),
             sub.startChar(),sub.endChar()));
           }
        }else if (buffer.length() + para.length() <= chunkSize) {
            // 2b. 短段落塞进缓冲区，能塞下就继续攒
            if (buffer.isEmpty()) {
                bufferStart = text.indexOf(para, bufferStart);
            }
            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(para);
            }
            else{
                chunks.add(new Chunk(index++,buffer.toString(),bufferStart,
            bufferStart+buffer.length()));
            bufferStart = text.indexOf(para,bufferStart);
            buffer.setLength(0);
            buffer.append(para);
            }
        }
        if(!buffer.isEmpty()){
            chunks.add(new Chunk(index++, buffer.toString(), bufferStart,bufferStart+buffer.length()));
        }
        return chunks;
    }
}

