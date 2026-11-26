package gg.modl.minecraft.replay.recording;

final class FoldingVisitor extends ForwardingVisitor {
    FoldingVisitor(WorldModel model, ChunkStore chunks) {
        super(model);
        model.listen(chunks::fold);
    }
}
