import module ax.xz.mri;

class MriIterativeRecon implements Script {
    public void run(ScriptContext ctx) throws InterruptedException {
        ctx.status("MRI reconstruction starter — replace with SENSE / CS-MRI loop");
        ctx.summary("");
    }
    void main() { NMRStudio.runScript(new MriIterativeRecon()); }
}
