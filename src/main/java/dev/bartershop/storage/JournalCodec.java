package dev.bartershop.storage;

import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.StockStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact, versioned encoding for recovery journals.
 *
 * <pre>
 * V1
 * STOCK
 * amount  material  signature  maxStack  base64
 * ...
 * PROCEEDS
 * amount  material  signature  maxStack  base64
 * ...
 * </pre>
 *
 * Fields are tab separated and base64 never contains a tab or newline. The
 * display name is intentionally omitted: it is cosmetic and can be rebuilt
 * from the material. The full serialised item blob is preserved so a recovered
 * item keeps every component.
 */
public final class JournalCodec {

    public static final String VERSION = "V1";
    private static final String EMPTY = "-";

    private JournalCodec() {
    }

    public static String encodeTrade(List<StockStack> stock, List<StockStack> proceeds) {
        StringBuilder out = new StringBuilder(VERSION).append('\n');
        out.append("STOCK\n");
        appendStacks(out, stock);
        out.append("PROCEEDS\n");
        appendStacks(out, proceeds);
        return out.toString();
    }

    public static String encodeItems(List<StockStack> items) {
        StringBuilder out = new StringBuilder(VERSION).append('\n');
        appendStacks(out, items);
        return out.toString();
    }

    public static TradePayload decodeTrade(String payload) {
        String[] lines = payload.split("\n", -1);
        if (lines.length < 3 || !VERSION.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported journal payload");
        }
        int stockIndex = -1;
        int proceedsIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("STOCK".equals(lines[i])) {
                stockIndex = i;
            } else if ("PROCEEDS".equals(lines[i])) {
                proceedsIndex = i;
            }
        }
        if (stockIndex < 0 || proceedsIndex < 0 || proceedsIndex < stockIndex) {
            throw new IllegalArgumentException("journal payload is missing a section");
        }
        List<StockStack> stock = decodeRange(lines, stockIndex + 1, proceedsIndex);
        List<StockStack> proceeds = decodeRange(lines, proceedsIndex + 1, lines.length);
        return new TradePayload(stock, proceeds);
    }

    public static List<StockStack> decodeItems(String payload) {
        String[] lines = payload.split("\n", -1);
        if (lines.length == 0 || !VERSION.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported journal payload");
        }
        return decodeRange(lines, 1, lines.length);
    }

    private static void appendStacks(StringBuilder out, List<StockStack> stacks) {
        if (stacks.isEmpty()) {
            out.append(EMPTY).append('\n');
            return;
        }
        for (StockStack stack : stacks) {
            ItemData item = stack.item();
            out.append(stack.amount()).append('\t')
                    .append(item.material()).append('\t')
                    .append(item.signature()).append('\t')
                    .append(item.maxStackSize()).append('\t')
                    .append(item.serialized()).append('\n');
        }
    }

    private static List<StockStack> decodeRange(String[] lines, int from, int to) {
        List<StockStack> stacks = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String line = lines[i];
            if (line.isEmpty() || EMPTY.equals(line)) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("malformed journal item line");
            }
            try {
                int amount = Integer.parseInt(parts[0]);
                String material = parts[1];
                String signature = parts[2];
                int maxStack = Integer.parseInt(parts[3]);
                String serialized = parts[4];
                stacks.add(new StockStack(new ItemData(material, signature, serialized, maxStack, null), amount));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("malformed journal item entry: " + e.getMessage(), e);
            }
        }
        return stacks;
    }

    public record TradePayload(List<StockStack> stock, List<StockStack> proceeds) {
    }
}
