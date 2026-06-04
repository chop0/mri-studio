package ax.xz.mri.starters;

import module ax.xz.mri;

class Blank implements Script {
    public void run(ScriptContext ctx) throws InterruptedException {
        ctx.status("running…");
        // Drop in your experiment — call ctx.observationSource().run(segments, pulse)
        // to fire a pulse sequence, ctx.show(...) to plot, ctx.put(...) to stash
        // a result, ctx.checkpoint() to honour the Stop button.
        ctx.summary("done");
    }
    void main() { NMRStudio.runScript(new Blank()); }
}
