import { useState, useRef, useEffect, useCallback, useMemo } from "react";

/* ─── CONSTANTS ─── */
const P=["#22c55e","#06b6d4","#f59e0b","#ef4444","#a855f7","#e11d48","#7c3aed","#0891b2","#ca8a04","#4f46e5"];
const BG="#0b0b12",BG2="#101018",GR="#1a1a28",TX="#94a3b8",TX2="#4a5568",AC="#3b82f6",CUR="#f59e0b";

/* ─── SIMULATOR ─── */
function bilerp(g,rA,zA,r,z){
  let ri=(r-rA[0])/(rA[rA.length-1]-rA[0])*(rA.length-1), zi=(z-zA[0])/(zA[zA.length-1]-zA[0])*(zA.length-1);
  ri=Math.max(0,Math.min(rA.length-1.001,ri)); zi=Math.max(0,Math.min(zA.length-1.001,zi));
  const r0=Math.floor(ri), z0=Math.floor(zi);
  const fr=ri-r0, fz=zi-z0, r1=Math.min(r0+1,rA.length-1), z1=Math.min(z0+1,zA.length-1);
  return(1-fr)*(1-fz)*g[r0][z0]+fr*(1-fz)*g[r1][z0]+(1-fr)*fz*g[r0][z1]+fr*fz*g[r1][z1];
}
function getF(D,xm,zm){
  const f=D.field,rA=f.r_mm,zA=f.z_mm,B=f.B0n,rm=Math.abs(xm)*1e3,zmm=zm*1e3;
  const dBz=bilerp(f.dBz_uT,rA,zA,rm,zmm)*1e-6;
  let mx0=0,my0=0,mz0=1;
  if(f.Mx0){mx0=bilerp(f.Mx0,rA,zA,rm,zmm);my0=bilerp(f.My0,rA,zA,rm,zmm);mz0=bilerp(f.Mz0,rA,zA,rm,zmm)}
  return{dBz,mx0,my0,mz0,gxm:xm+zm*zm/(2*B),gzm:zm+(xm/2)**2/(2*B),b1s:1+.12*(xm/(f.FOV_X/2))**2+.08*(zm/(f.FOV_Z/2))**2};
}
function sim(D,rmm,zmm,pulse){
  if(!D?.field?.segments||!pulse)return null;
  const f=D.field,fl=getF(D,rmm*1e-3,zmm*1e-3),ga=f.gamma,om0=ga*fl.dBz;
  let mx=0,my=0,mz=1;const o=[];let t=0; // start from equilibrium
  const segs=f.segments;
  for(let si=0;si<segs.length&&si<pulse.length;si++){
    const seg=segs[si],steps=pulse[si],dt=seg.dt,nf=seg.n_free;
    const E2=Math.exp(-dt/f.T2),E1=Math.exp(-dt/f.T1);
    for(let j=0;j<steps.length;j++){
      o.push(+(t*1e6).toFixed(1),+mx.toFixed(5),+my.toFixed(5),+mz.toFixed(5),j<nf?0:1);
      const u=steps[j];
      if(j<nf){const om=om0+ga*(u[2]*fl.gxm+u[3]*fl.gzm),th=om*dt,c=Math.cos(th),s=Math.sin(th);const a=(mx*c-my*s)*E2,b=(mx*s+my*c)*E2;mx=a;my=b;mz=1+(mz-1)*E1}
      else{const bx=u[0]*fl.b1s,by=u[1]*fl.b1s,bz=fl.dBz+u[2]*fl.gxm+u[3]*fl.gzm,Bm=Math.sqrt(bx*bx+by*by+bz*bz+1e-60),th=ga*Bm*dt,nx=bx/Bm,ny=by/Bm,nz=bz/Bm,c=Math.cos(th),s=Math.sin(th),oc=1-c,nd=nx*mx+ny*my+nz*mz,cx=ny*mz-nz*my,cy=nz*mx-nx*mz,cz=nx*my-ny*mx,a=(mx*c+cx*s+nx*nd*oc)*E2,b=(my*c+cy*s+ny*nd*oc)*E2;mx=a;my=b;mz=1+(mz*c+cz*s+nz*nd*oc-1)*E1}
      t+=dt;
    }
  }
  o.push(+(t*1e6).toFixed(1),+mx.toFixed(5),+my.toFixed(5),+mz.toFixed(5),2);return o;
}
/** Simulate to time tC_us (microseconds), return [mx,my,mz]. Early-exits. */
function simTo(D,rmm,zmm,pulse,tC_us){
  if(!D?.field?.segments||!pulse)return[0,0,1];
  const f=D.field,fl=getF(D,rmm*1e-3,zmm*1e-3),ga=f.gamma,om0=ga*fl.dBz;
  let mx=0,my=0,mz=1,t=0;
  const segs=f.segments;
  for(let si=0;si<segs.length&&si<pulse.length;si++){
    const seg=segs[si],steps=pulse[si],dt=seg.dt,nf=seg.n_free;
    const E2=Math.exp(-dt/f.T2),E1=Math.exp(-dt/f.T1);
    for(let j=0;j<steps.length;j++){
      if(t*1e6>=tC_us)return[mx,my,mz];
      const u=steps[j];
      if(j<nf){const om=om0+ga*(u[2]*fl.gxm+u[3]*fl.gzm),th=om*dt,c=Math.cos(th),s=Math.sin(th);const a=(mx*c-my*s)*E2,b=(mx*s+my*c)*E2;mx=a;my=b;mz=1+(mz-1)*E1}
      else{const bx=u[0]*fl.b1s,by=u[1]*fl.b1s,bz=fl.dBz+u[2]*fl.gxm+u[3]*fl.gzm,Bm=Math.sqrt(bx*bx+by*by+bz*bz+1e-60),th=ga*Bm*dt,nx=bx/Bm,ny=by/Bm,nz=bz/Bm,c=Math.cos(th),s=Math.sin(th),oc=1-c,nd=nx*mx+ny*my+nz*mz,cx=ny*mz-nz*my,cy=nz*mx-nx*mz,cz=nx*my-ny*mx,a=(mx*c+cx*s+nx*nd*oc)*E2,b=(my*c+cy*s+ny*nd*oc)*E2;mx=a;my=b;mz=1+(mz*c+cz*s+nz*nd*oc-1)*E1}
      t+=dt;
    }
  }
  return[mx,my,mz];
}
function getPulse(D,mode,scen,gIters,gIdx){
  if(!D?.pulses)return null;
  // When GRAPE scenario selected and iterations exist, use the selected iteration
  if((mode==="grape"||scen==="GRAPE")&&D.pulses.grape&&gIters.length>0)return D.pulses.grape[String(gIters[gIdx])];
  return D.pulses[scen];
}
function stateAt(traj,tc){if(!traj||traj.length<10)return null;for(let i=0;i<traj.length-5;i+=5){if(traj[i+5]>=tc){const a=traj[i],b=traj[i+5],f=b===a?0:(tc-a)/(b-a);return[traj[i+1]+f*(traj[i+6]-traj[i+1]),traj[i+2]+f*(traj[i+7]-traj[i+2]),traj[i+3]+f*(traj[i+8]-traj[i+3])]}}const j=traj.length-5;return[traj[j+1],traj[j+2],traj[j+3]]}

/* ─── PHASE MAP COMPUTATION ─── */
function compPhaseZ(D,pulse){
  if(!D?.field||!pulse)return null;
  const nZ=50,zArr=[];for(let i=0;i<nZ;i++)zArr.push(-6+12*i/(nZ-1));
  const step=4,data=[];
  for(const z of zArr){const tr=sim(D,0,z,pulse),row=[];if(!tr){data.push(row);continue}
    for(let it=0;it*step*5<tr.length-4;it++){const j=it*step*5;row.push({t:tr[j],ph:Math.atan2(tr[j+2],tr[j+1])*180/Math.PI,mp:Math.sqrt(tr[j+1]**2+tr[j+2]**2)})}
    data.push(row)}
  return{yArr:zArr,data,nY:nZ};
}
function compPhaseR(D,pulse){
  if(!D?.field||!pulse)return null;
  const nR=20,rArr=[];for(let i=0;i<nR;i++)rArr.push(i/(nR-1)*30);
  const step=4,data=[];
  for(const r of rArr){const tr=sim(D,r,0,pulse),row=[];if(!tr){data.push(row);continue}
    for(let it=0;it*step*5<tr.length-4;it++){const j=it*step*5;row.push({t:tr[j],ph:Math.atan2(tr[j+2],tr[j+1])*180/Math.PI,mp:Math.sqrt(tr[j+1]**2+tr[j+2]**2)})}
    data.push(row)}
  return{yArr:rArr,data,nY:nR,label:"r [mm]",ticks:[0,10,20,30]};
}

/* ─── CANVAS SETUP ─── */
function su(cv){if(!cv)return null;const d=devicePixelRatio||1,r=cv.getBoundingClientRect();cv.width=r.width*d;cv.height=r.height*d;const x=cv.getContext("2d");x.scale(d,d);return{x,w:r.width,h:r.height}}
function pr(mx,my,mz,th,ph,sc,cx,cy){const ct=Math.cos(th),st=Math.sin(th),cp=Math.cos(ph),sp=Math.sin(ph);return[cx+(mx*ct-my*st)*sc,cy+(mx*st*sp+my*ct*sp-mz*cp)*sc,-(mx*st*cp+my*ct*cp+mz*sp)]}
const cl=(v,lo,hi)=>Math.max(lo,Math.min(hi,v));

/* ─── DRAW: SPHERE ─── */
function drawSphere(cv,S){
  const s=su(cv);if(!s)return;const{x,w,h}=s,cx=w/2,cy=h/2,{th,ph,zm}=S.cam,sc=Math.min(w,h)*.37*zm;
  x.fillStyle=BG;x.fillRect(0,0,w,h);
  for(const fn of[a=>pr(Math.cos(a),Math.sin(a),0,th,ph,sc,cx,cy),a=>pr(Math.cos(a),0,Math.sin(a),th,ph,sc,cx,cy),a=>pr(0,Math.cos(a),Math.sin(a),th,ph,sc,cx,cy)]){
    for(let pass=0;pass<2;pass++){x.beginPath();let st=false;for(let i=0;i<=80;i++){const p=fn(i/80*Math.PI*2);if((pass===0&&p[2]<=0)||(pass===1&&p[2]>0)){if(!st){x.moveTo(p[0],p[1]);st=true}else x.lineTo(p[0],p[1])}else st=false}x.strokeStyle=pass?"rgba(255,255,255,.1)":"rgba(255,255,255,.03)";x.lineWidth=pass?.6:.4;x.stroke()}}
  for(const[d,l,c]of[[[1.15,0,0],"Mx","#ef4444"],[[0,1.15,0],"My","#22c55e"],[[0,0,1.15],"Mz","#3b82f6"]]){const p0=pr(0,0,0,th,ph,sc,cx,cy),p1=pr(...d,th,ph,sc,cx,cy),dt=(1+p1[2])/2;x.strokeStyle=c;x.lineWidth=.5+.5*dt;x.globalAlpha=.15+.35*dt;x.beginPath();x.moveTo(p0[0],p0[1]);x.lineTo(p1[0],p1[1]);x.stroke();x.fillStyle=c;x.font=`600 ${10+dt}px monospace`;x.globalAlpha=.35+.4*dt;x.fillText(l,p1[0]+4,p1[1]-3);x.globalAlpha=1}
  S.isos.forEach(o=>{if(!o.v||!o.t)return;
    const pts=[];for(let i=0;i<o.t.length;i+=5)pts.push(o.t.slice(i,i+5));
    const vis=[];for(const p of pts){if(p[0]>=S.tS&&p[0]<=S.tE)vis.push(p);else if(p[0]>S.tE&&vis.length>0)break}
    if(!vis.length)return;
    let ss=0;for(let i=1;i<=vis.length;i++){if(i===vis.length||vis[i][4]!==vis[i-1][4]){const sg=vis.slice(ss,i);if(sg.length>=2){const isP=sg[0][4]===1;let ad=0;for(const v of sg)ad+=pr(v[1],v[2],v[3],th,ph,sc,cx,cy)[2];ad/=sg.length;const dF=.3+.7*(1+ad)/2;x.strokeStyle=o.c;x.lineWidth=isP?1.8:1;x.globalAlpha=(isP?.8:.1)*dF;x.beginPath();for(let j=0;j<sg.length;j++){const p=pr(sg[j][1],sg[j][2],sg[j][3],th,ph,sc,cx,cy);j?x.lineTo(p[0],p[1]):x.moveTo(p[0],p[1])}x.stroke();x.globalAlpha=1}ss=i}}
    const st=stateAt(o.t,S.tC);if(!st)return;const[mx,my,mz]=st,mg=Math.sqrt(mx*mx+my*my+mz*mz),mp=Math.sqrt(mx*mx+my*my);
    const ux=mg>1e-6?mx/mg:0,uy=mg>1e-6?my/mg:0,uz=mg>1e-6?mz/mg:0;
    const pS=pr(ux,uy,uz,th,ph,sc,cx,cy),dF=.5+.5*(1+pS[2])/2;
    x.fillStyle=o.c;x.globalAlpha=dF;x.beginPath();x.arc(pS[0],pS[1],3+2*dF,0,Math.PI*2);x.fill();x.strokeStyle="rgba(0,0,0,.4)";x.lineWidth=1;x.beginPath();x.arc(pS[0],pS[1],3+2*dF,0,Math.PI*2);x.stroke();x.globalAlpha=1;
    if(S.showMp&&mp>.01){const pA=pr(mx,my,0,th,ph,sc,cx,cy);x.setLineDash([3,3]);x.strokeStyle=o.c;x.globalAlpha=.35;x.lineWidth=1;x.beginPath();x.moveTo(pS[0],pS[1]);x.lineTo(pA[0],pA[1]);x.stroke();x.setLineDash([]);x.strokeStyle=o.c;x.lineWidth=1.5;x.globalAlpha=.5+.3*mp;x.beginPath();x.arc(pA[0],pA[1],2+4*mp,0,Math.PI*2);x.stroke();x.fillStyle=o.c;x.font="8px monospace";x.globalAlpha=.45;x.fillText("|M⊥|="+mp.toFixed(2),pA[0]+8,pA[1]+3);x.globalAlpha=1}
  });
}

/* ─── DRAW: WAVEFORM TIMELINE ─── */
function drawTL(cv,S){
  const s=su(cv);if(!s||!S.D?.field?.segments||!S.pulse)return;const{x,w,h}=s;
  const f=S.D.field,segs=f.segments;
  // Compute total time and segment boundaries
  const segBounds=[];let tAcc=0;
  for(const seg of segs){const ns=seg.n_free+seg.n_pulse;segBounds.push({t0:tAcc,tF:tAcc+seg.n_free*seg.dt*1e6,tE:tAcc+ns*seg.dt*1e6,dt:seg.dt,nf:seg.n_free});tAcc+=ns*seg.dt*1e6}
  const mt=tAcc;
  const pad={l:40,r:6,t:2,b:12},pW=w-pad.l-pad.r,pH=h-pad.t-pad.b;
  x.fillStyle=BG;x.fillRect(0,0,w,h);
  const tracks=[{l:"|B₁|",mx:250e-6,fn:u=>Math.sqrt(u[0]**2+u[1]**2),c:"#f59e0b",fill:1},{l:"Gz",mx:.035,fn:u=>u[3],c:"#3b82f6",ctr:1},{l:"Gx",mx:.035,fn:u=>u[2],c:"#ef4444",ctr:1}];
  const tH=pH/tracks.length,vS=S.vS,vE=S.vE,vSpan=vE-vS;
  const tPx=t=>pad.l+(t-vS)/vSpan*pW;
  tracks.forEach((tr,ti)=>{const y0=pad.t+ti*tH;
    x.fillStyle=ti%2?BG2:BG;x.fillRect(pad.l,y0,pW,tH);
    x.strokeStyle=GR;x.lineWidth=.5;x.beginPath();x.moveTo(pad.l,y0+tH);x.lineTo(pad.l+pW,y0+tH);x.stroke();
    x.fillStyle=TX2;x.font="bold 8px monospace";x.textAlign="right";x.fillText(tr.l,pad.l-4,y0+tH/2+3);x.textAlign="left";
    if(tr.ctr){x.strokeStyle="rgba(255,255,255,.04)";x.lineWidth=.5;x.beginPath();x.moveTo(pad.l,y0+tH/2);x.lineTo(pad.l+pW,y0+tH/2);x.stroke()}
    // Segment shading
    segBounds.forEach((sb,si)=>{if(sb.tE<vS||sb.t0>vE)return;
      const xF=Math.max(pad.l,tPx(sb.tF)),xE=Math.min(pad.l+pW,tPx(sb.tE));
      x.fillStyle="rgba(255,255,255,.02)";x.fillRect(xF,y0,xE-xF,tH);
      if(si>0){const xD=tPx(sb.t0);x.strokeStyle="rgba(255,255,255,.06)";x.lineWidth=.5;x.beginPath();x.moveTo(xD,y0);x.lineTo(xD,y0+tH);x.stroke()}});
    // Waveform — iterate per segment with per-segment dt
    x.save();x.beginPath();x.rect(pad.l,y0,pW,tH);x.clip();
    x.beginPath();x.strokeStyle=tr.c;x.lineWidth=1.2;x.globalAlpha=.8;let started=false;
    for(let si=0;si<segs.length&&si<S.pulse.length;si++){
      const seg=segs[si],steps=S.pulse[si],dt=seg.dt;
      let t=segBounds[si].t0;
      for(let j=0;j<steps.length;j++){
        if(t>=vS-vSpan*.01&&t<=vE+vSpan*.01){
          const v=tr.fn(steps[j]),px=tPx(t);
          let py;if(tr.ctr)py=y0+tH/2-(v/tr.mx)*tH/2;else py=y0+tH-(v/tr.mx)*tH*.85;
          if(!started){x.moveTo(px,py);started=true}else x.lineTo(px,py);
        }
        t+=dt*1e6;
      }
    }
    x.stroke();x.globalAlpha=1;x.restore()});
  // Window handles
  const xS=cl(tPx(S.tS),pad.l,pad.l+pW),xE=cl(tPx(S.tE),pad.l,pad.l+pW);
  x.fillStyle=AC;x.globalAlpha=.06;x.fillRect(xS,pad.t,xE-xS,pH);x.globalAlpha=1;
  for(const xh of[xS,xE]){x.fillStyle=AC;x.globalAlpha=.7;x.fillRect(xh-1.5,pad.t,3,pH);x.globalAlpha=1}
  // Cursor tC
  const xC=cl(tPx(S.tC),pad.l,pad.l+pW);
  x.strokeStyle=CUR;x.lineWidth=1.5;x.globalAlpha=.8;x.beginPath();x.moveTo(xC,pad.t);x.lineTo(xC,pad.t+pH);x.stroke();x.globalAlpha=1;
  // Segment numbers
  x.font="bold 7px monospace";x.textAlign="center";x.fillStyle=TX2;x.globalAlpha=.3;
  segBounds.forEach((sb,si)=>{if(sb.tE<vS||sb.t0>vE)return;x.fillText(si,(tPx(sb.t0)+tPx(sb.tE))/2,pad.t+8)});
  x.textAlign="left";x.globalAlpha=1;
  // Time ticks
  x.fillStyle=TX2;x.font="7px monospace";x.textAlign="center";x.globalAlpha=.4;
  const ts=vSpan>5000?2000:vSpan>2000?1000:vSpan>800?500:200;
  for(let t=Math.ceil(vS/ts)*ts;t<=vE;t+=ts){const px=tPx(t);if(px>pad.l+4&&px<pad.l+pW-4)x.fillText((t/1000).toFixed(t%1000?1:0)+"ms",px,h-2)}
  x.textAlign="left";x.globalAlpha=1;
}

/* ─── DRAW: ANGLE PLOTS ─── */
function drawPlots(cv,S){
  const s=su(cv);if(!s||!S.D)return;const{x,w,h}=s;x.fillStyle=BG;x.fillRect(0,0,w,h);
  const vis=S.isos.filter(o=>o.v&&o.t);
  const pad={l:40,r:8,t:14,b:18,gap:10},pW=(w-pad.l-pad.r-pad.gap*2)/3,pH=h-pad.t-pad.b;
  const tMn=S.tS,tMx=Math.max(S.tE,S.tS+1),tSpan=tMx-tMn;
  const plots=[
    {t:"Phase φ",u:"°",mn:-180,mx:180,tk:[-180,-90,0,90,180],fn:(a,b)=>{const m=Math.sqrt(a*a+b*b);return m>.01?Math.atan2(b,a)*180/Math.PI:NaN}},
    {t:"Polar θ",u:"°",mn:0,mx:180,tk:[0,45,90,135,180],fn:(a,b,c)=>Math.atan2(Math.sqrt(a*a+b*b),c)*180/Math.PI},
    {t:"|M⊥|",u:"",mn:0,mx:1,tk:[0,.25,.5,.75,1],fn:(a,b)=>Math.sqrt(a*a+b*b)}
  ];
  for(let pi=0;pi<3;pi++){
    const p=plots[pi],ox=pad.l+pi*(pW+pad.gap),oy=pad.t;
    const yP=v=>oy+pH-(v-p.mn)/(p.mx-p.mn)*pH, xP=t=>ox+(t-tMn)/tSpan*pW;
    // Y grid + labels
    x.textAlign="right";
    for(const v of p.tk){
      const y=yP(v);
      x.strokeStyle=GR;x.lineWidth=v===0?.6:.3;x.globalAlpha=v===0?.5:.2;
      x.beginPath();x.moveTo(ox,y);x.lineTo(ox+pW,y);x.stroke();x.globalAlpha=1;
      x.fillStyle=TX;x.font="8px monospace";
      const lbl=p.u==="°"?v+"°":(v%1?v.toFixed(2):""+v);
      x.fillText(lbl,ox-4,y+3);
    }
    x.textAlign="left";
    // Axis frame
    x.strokeStyle="rgba(255,255,255,.1)";x.lineWidth=.5;
    x.beginPath();x.moveTo(ox,oy);x.lineTo(ox,oy+pH);x.lineTo(ox+pW,oy+pH);x.stroke();
    // X ticks
    const xTs=tSpan>5000?2000:tSpan>2000?1000:tSpan>800?200:tSpan>300?100:50;
    x.fillStyle=TX2;x.font="7px monospace";x.textAlign="center";x.globalAlpha=.5;
    for(let t=Math.ceil(tMn/xTs)*xTs;t<=tMx;t+=xTs){
      const px=xP(t);if(px>ox+4&&px<ox+pW-4){
        x.fillText(tSpan>2000?(t/1000).toFixed(t%1000?1:0):t+"",px,oy+pH+10);
        x.strokeStyle="rgba(255,255,255,.04)";x.lineWidth=.3;
        x.beginPath();x.moveTo(px,oy);x.lineTo(px,oy+pH);x.stroke();
      }
    }
    // X unit on rightmost plot
    if(pi===2){x.textAlign="right";x.fillText(tSpan>2000?"ms":"μs",ox+pW,oy+pH+10);x.textAlign="left"}
    x.globalAlpha=1;
    // tC cursor
    const xC=xP(S.tC);x.strokeStyle=CUR;x.lineWidth=1;x.globalAlpha=.5;
    x.beginPath();x.moveTo(xC,oy);x.lineTo(xC,oy+pH);x.stroke();x.globalAlpha=1;
    // Title
    x.fillStyle=TX;x.font="bold 10px monospace";x.textAlign="center";
    x.fillText(p.t,ox+pW/2,oy-3);x.textAlign="left";
    // Traces (clipped)
    x.save();x.beginPath();x.rect(ox,oy-1,pW,pH+2);x.clip();
    for(const o of vis){
      x.strokeStyle=o.c;x.lineWidth=1.2;x.globalAlpha=.8;x.beginPath();let st=false;
      for(let i=0;i<o.t.length;i+=5){const t=o.t[i];if(t<tMn-tSpan*.02||t>tMx+tSpan*.02)continue;const v=p.fn(o.t[i+1],o.t[i+2],o.t[i+3]);if(isNaN(v)){st=false;continue}const px=xP(t),py=yP(v);st?x.lineTo(px,py):x.moveTo(px,py);st=true}
      x.stroke();x.globalAlpha=1;
      const sa=stateAt(o.t,S.tC);if(sa){const ve=p.fn(...sa);if(!isNaN(ve)){x.fillStyle=o.c;x.beginPath();x.arc(xP(S.tC),yP(ve),3,0,Math.PI*2);x.fill()}}
    }
    x.restore();
  }
}

/* ─── DRAW: PHASE HEATMAP ─── */
function drawPhaseMap(cv,pm,S,cfg){
  const s=su(cv);if(!s||!pm)return;const{x,w,h}=s;x.fillStyle=BG;x.fillRect(0,0,w,h);
  const{yArr,data,nY}=pm,tMn=S.tS,tMx=Math.max(S.tE,S.tS+1);
  const pad={l:34,r:4,t:14,b:16},pW=w-pad.l-pad.r,pH=h-pad.t-pad.b;
  x.fillStyle=TX;x.font="bold 9px monospace";x.textAlign="center";x.fillText(cfg.title,pad.l+pW/2,pad.t-3);x.textAlign="left";
  // Y labels
  x.fillStyle=TX;x.font="8px monospace";x.textAlign="right";
  for(const v of(cfg.ticks||[])){const y=pad.t+pH*(1-(v-yArr[0])/(yArr[yArr.length-1]-yArr[0]));x.fillText(""+v,pad.l-3,y+2);x.strokeStyle="rgba(255,255,255,.04)";x.lineWidth=.3;x.beginPath();x.moveTo(pad.l,y);x.lineTo(pad.l+pW,y);x.stroke()}
  x.textAlign="left";
  // Heatmap cells
  const cellH=pH/nY;
  for(let iy=0;iy<nY;iy++){const row=data[iy],y=pad.t+pH-((iy+.5)/nY)*pH;
    for(let it=0;it<row.length;it++){const d=row[it];if(d.t<tMn||d.t>tMx)continue;
      const xPos=pad.l+(d.t-tMn)/(tMx-tMn)*pW,nxt=row[it+1]?.t||d.t+40,cellW=Math.max(1,(nxt-d.t)/(tMx-tMn)*pW+1);
      const hue=((d.ph%360)+360)%360;
      const br=cl(d.mp,0,1);
      x.fillStyle=hue2rgb(hue, br);
      x.fillRect(xPos,y-cellH/2,cellW,cellH+1)}}
  // Slice boundaries for z-map
  if(cfg.sliceBounds&&S.D?.field){const sh=(S.D.field.slice_half||.005)*1e3;
    for(const zv of[-sh,sh]){const y=pad.t+pH*(1-(zv-yArr[0])/(yArr[yArr.length-1]-yArr[0]));x.strokeStyle="rgba(34,197,94,.3)";x.lineWidth=.5;x.setLineDash([3,3]);x.beginPath();x.moveTo(pad.l,y);x.lineTo(pad.l+pW,y);x.stroke();x.setLineDash([])}}
  // tC cursor
  const xC=pad.l+(S.tC-tMn)/(tMx-tMn)*pW;
  x.strokeStyle=CUR;x.lineWidth=1.5;x.globalAlpha=.8;x.beginPath();x.moveTo(xC,pad.t);x.lineTo(xC,pad.t+pH);x.stroke();x.globalAlpha=1;
  // triangle handle
  x.fillStyle=CUR;x.globalAlpha=.7;x.beginPath();x.moveTo(xC,pad.t+pH);x.lineTo(xC-4,pad.t+pH+6);x.lineTo(xC+4,pad.t+pH+6);x.fill();x.globalAlpha=1;
  // X-axis ticks
  const tSpan=tMx-tMn,xTs=tSpan>5000?2000:tSpan>2000?1000:tSpan>800?200:tSpan>300?100:50;
  x.fillStyle=TX2;x.font="7px monospace";x.textAlign="center";x.globalAlpha=.5;
  for(let t=Math.ceil(tMn/xTs)*xTs;t<=tMx;t+=xTs){
    const px=pad.l+(t-tMn)/tSpan*pW;if(px>pad.l+4&&px<pad.l+pW-4)
    x.fillText(tSpan>2000?(t/1000).toFixed(t%1000?1:0)+"ms":t+"μs",px,h-2);
  }
  x.textAlign="left";x.globalAlpha=1;
}

/* ─── DRAW: CROSS-SECTION (z vertical) ─── */
function hue2rgb(phase_deg, brightness){
  const hh=((phase_deg%360)+360)%360/60, hi=hh|0, f2=hh-hi;
  const q=1-f2;
  let r,g,b;
  switch(hi%6){case 0:r=1;g=f2;b=0;break;case 1:r=q;g=1;b=0;break;case 2:r=0;g=1;b=f2;break;case 3:r=0;g=q;b=1;break;case 4:r=f2;g=0;b=1;break;default:r=1;g=0;b=q}
  return `rgb(${~~(r*brightness*255)},${~~(g*brightness*255)},${~~(b*brightness*255)})`;
}
function drawXS(cv,S){
  const s=su(cv);if(!s||!S.D?.field)return;const{x,w,h}=s;
  const f=S.D.field,rA=f.r_mm,zA=f.z_mm,rMax=rA[rA.length-1],xsH=S.xsH;
  x.fillStyle=BG;x.fillRect(0,0,w,h);
  const pad={l:28,r:8,t:8,b:20},pW=w-pad.l-pad.r,pH=h-pad.t-pad.b;
  const zP=z=>pad.t+pH*(1-(z-(-xsH))/(2*xsH));
  const rP=r=>pad.l+r/rMax*pW;

  // Clip to plot area for all heatmap/cell drawing
  x.save();
  x.beginPath();x.rect(pad.l,pad.t,pW,pH);x.clip();

  // Phase-colored background: full sim to tC at each grid point
  // Use fixed sample points so colors don't shift when zooming
  if(S.pulse){
    const nR=15;
    const zMax=Math.min(xsH, zA[zA.length-1]); // clamp to field data range
    // Fixed z samples: dense near slice, sparse outside
    const zSamples=[];
    for(let z=-6;z<=6;z+=0.5) zSamples.push(z);
    for(let z=-zMax;z<-6;z+=Math.max(2,zMax/15)) zSamples.push(z);
    for(let z=6.5;z<=zMax;z+=Math.max(2,zMax/15)) zSamples.push(z);
    zSamples.sort((a,b)=>a-b);
    const nZ=zSamples.length;
    for(let ir=0;ir<nR;ir++){
      const r_mm=ir/(nR-1)*rMax;
      const x0=rP(r_mm),x1=ir<nR-1?rP((ir+1)/(nR-1)*rMax):pad.l+pW;
      for(let iz=0;iz<nZ;iz++){
        const z_mm=zSamples[iz];
        if(z_mm<-xsH||z_mm>xsH) continue;
        const y0=zP(z_mm);
        const z_next=iz<nZ-1?zSamples[iz+1]:z_mm+1;
        const y1=zP(Math.min(z_next, xsH));
        const [smx,smy,smz]=simTo(S.D,r_mm,z_mm,S.pulse,S.tC);
        const mp=Math.sqrt(smx*smx+smy*smy);
        const phase_deg=Math.atan2(smy,smx)*180/Math.PI;
        const rx=Math.min(x0,x1),ry=Math.min(y0,y1),rw=Math.abs(x1-x0)+1,rh=Math.abs(y1-y0)+1;
        x.fillStyle=hue2rgb(phase_deg, mp);
        x.fillRect(rx,ry,rw,rh);
      }
    }
  }

  x.restore(); // unclip

  // Slice boundaries — dashed lines only (no fill, to preserve |M⊥| brightness comparison)
  const sh=(f.slice_half||.005)*1e3;
  const yTop=zP(sh),yBot=zP(-sh);
  x.strokeStyle="rgba(34,197,94,.6)";x.lineWidth=1;x.setLineDash([4,3]);
  x.beginPath();x.moveTo(pad.l,yTop);x.lineTo(pad.l+pW,yTop);x.stroke();
  x.beginPath();x.moveTo(pad.l,yBot);x.lineTo(pad.l+pW,yBot);x.stroke();
  x.setLineDash([]);
  // "slice" label on left margin
  x.fillStyle="rgba(34,197,94,.6)";x.font="bold 7px monospace";x.textAlign="left";
  x.fillText("slice",pad.l+2,(yTop+yBot)/2+3);

  // Axes frame
  x.strokeStyle="rgba(255,255,255,.12)";x.lineWidth=.5;
  x.beginPath();x.moveTo(pad.l,pad.t);x.lineTo(pad.l,pad.t+pH);x.lineTo(pad.l+pW,pad.t+pH);x.stroke();

  // Z ticks (left axis)
  x.fillStyle=TX2;x.font="7px monospace";x.textAlign="right";x.globalAlpha=.7;
  const zTickStep=xsH>50?50:xsH>20?10:xsH>8?5:2;
  for(let z=-Math.floor(xsH/zTickStep)*zTickStep;z<=xsH;z+=zTickStep){
    const y=zP(z);if(y<pad.t+2||y>pad.t+pH-2)continue;
    x.fillText(z+"",pad.l-3,y+3);
    x.strokeStyle="rgba(255,255,255,.05)";x.lineWidth=.3;
    x.beginPath();x.moveTo(pad.l,y);x.lineTo(pad.l+pW,y);x.stroke();
  }
  // R ticks (bottom axis)
  x.textAlign="center";
  const rTickStep=rMax>60?20:rMax>30?10:5;
  for(let r=0;r<=rMax;r+=rTickStep){
    const px=rP(r);if(px<pad.l+2||px>pad.l+pW-2)continue;
    x.fillText(r+"",px,pad.t+pH+11);
    x.strokeStyle="rgba(255,255,255,.05)";x.lineWidth=.3;
    x.beginPath();x.moveTo(px,pad.t);x.lineTo(px,pad.t+pH);x.stroke();
  }
  // Axis titles
  x.fillStyle=TX2;x.font="bold 7px monospace";x.globalAlpha=.5;
  x.textAlign="center";x.fillText("r [mm]",pad.l+pW/2,h-2);
  x.save();x.translate(8,pad.t+pH/2);x.rotate(-Math.PI/2);x.fillText("z [mm]",0,0);x.restore();
  x.globalAlpha=1;x.textAlign="left";

  // Isochromat dots — ring in identity color, fill in identity color
  S.isos.forEach(o=>{
    const px=rP(o.r),py=zP(o.z);if(py<pad.t-5||py>pad.t+pH+5||px<pad.l-5||px>pad.l+pW+5)return;
    x.fillStyle=o.c;x.globalAlpha=o.v?.9:.1;x.beginPath();x.arc(px,py,4,0,Math.PI*2);x.fill();
    x.strokeStyle="rgba(0,0,0,.6)";x.lineWidth=1;x.globalAlpha=o.v?.7:.1;x.beginPath();x.arc(px,py,4,0,Math.PI*2);x.stroke();
    x.globalAlpha=1;
  });
}

/* ─── MAIN COMPONENT ─── */
export default function App(){
  const[D,setD]=useState(null),[mode,setMode]=useState("fixed"),[scen,setScen]=useState(""),[gIdx,setGIdx]=useState(0);
  const[tS,setTS]=useState(0),[tE,setTE]=useState(1000),[vS,setVS]=useState(0),[vE,setVE]=useState(1000),[tC,setTC]=useState(1000);
  const[cam,setCam]=useState({th:.72,ph:.38,zm:1}),[isos,setIsos]=useState([]),[showMp,setSMp]=useState(false);
  const[xsH,setXsH]=useState(20),[nCI,setNCI]=useState(0);
  const[pmZ,setPmZ]=useState(null),[pmR,setPmR]=useState(null),[dragOver,setDragOver]=useState(false);
  const refs={sphere:useRef(),tl:useRef(),plots:useRef(),pmz:useRef(),pmr:useRef(),xs:useRef()};
  const R=useRef({});// shared ref for event handlers

  const mt=D?.field?.segments?D.field.segments.reduce((s,seg)=>s+(seg.n_free+seg.n_pulse)*seg.dt*1e6,0):1000;
  const gIters=useMemo(()=>D?.grape?Object.keys(D.grape).map(Number).sort((a,b)=>a-b):[],[D]);
  const pulse=useMemo(()=>getPulse(D,mode,scen,gIters,gIdx),[D,mode,scen,gIdx,gIters]);

  // Keep ref current for event handlers
  R.current={D,pulse,isos,setIsos,cam,setCam,tS,setTS,tE,setTE,vS,setVS,vE,setVE,tC,setTC,mt,xsH,setXsH,showMp,nCI,setNCI};

  // Recompute trajectories when pulse changes
  useEffect(()=>{if(!D||!pulse)return;setIsos(prev=>prev.map(o=>({...o,t:sim(D,o.r,o.z,pulse)})));setPmZ(compPhaseZ(D,pulse));setPmR(compPhaseR(D,pulse))},[D,pulse]);

  // Draw everything
  const S={D,pulse,isos,cam,tS,tE,vS,vE,tC,showMp,xsH};
  useEffect(()=>{drawSphere(refs.sphere.current,S);drawTL(refs.tl.current,S);drawPlots(refs.plots.current,S);drawPhaseMap(refs.pmz.current,pmZ,S,{title:"φ(z, t) at r=0",ticks:[-4,-2,0,2,4],sliceBounds:true});drawPhaseMap(refs.pmr.current,pmR,S,{title:"φ(r, t) at z=0",ticks:[0,10,20,30]});drawXS(refs.xs.current,S)});

  // Sphere events
  useEffect(()=>{const cv=refs.sphere.current;if(!cv)return;let dr=false,lx=0,ly=0;
    const dn=e=>{dr=true;lx=e.clientX;ly=e.clientY};
    const mv=e=>{if(!dr)return;const dx=e.clientX-lx,dy=e.clientY-ly;lx=e.clientX;ly=e.clientY;R.current.setCam(c=>({...c,th:c.th+dx*.008,ph:cl(c.ph+dy*.008,-1.4,1.4)}))};
    const up=()=>dr=false;
    const wh=e=>{e.preventDefault();R.current.setCam(c=>({...c,zm:cl(c.zm*(e.deltaY>0?.9:1.1),.5,5)}))};
    cv.addEventListener("pointerdown",dn);window.addEventListener("pointermove",mv);window.addEventListener("pointerup",up);cv.addEventListener("wheel",wh,{passive:false});
    return()=>{cv.removeEventListener("pointerdown",dn);window.removeEventListener("pointermove",mv);window.removeEventListener("pointerup",up);cv.removeEventListener("wheel",wh)}},[!!D]);

  // Timeline events
  useEffect(()=>{const cv=refs.tl.current;if(!cv)return;let drag=null;
    const pad=40,rPad=6;
    const tAt=(e)=>{const rr=cv.getBoundingClientRect(),fx=(e.clientX-rr.left-pad)/(rr.width-pad-rPad);return R.current.vS+fx*(R.current.vE-R.current.vS)};
    const dn=e=>{const t=tAt(e),{tS,tE,vS,vE}=R.current,vSpan=vE-vS,hw=vSpan*.02;
      if(Math.abs(t-R.current.tC)<hw+8)drag={type:"C",x0:e.clientX,oTC:R.current.tC};
      else if(Math.abs(t-tS)<hw+10)drag={type:"L",x0:e.clientX,oS:tS,oE:tE};
      else if(Math.abs(t-tE)<hw+10)drag={type:"R",x0:e.clientX,oS:tS,oE:tE};
      else if(t>tS&&t<tE)drag={type:"P",x0:e.clientX,oS:tS,oE:tE};
      else{if(Math.abs(t-tS)<Math.abs(t-tE))R.current.setTS(cl(t,vS,tE-10));else R.current.setTE(cl(t,tS+10,vE))}
      cv.setPointerCapture(e.pointerId)};
    const mv=e=>{if(!drag)return;const rr=cv.getBoundingClientRect(),{vS,vE,tS,tE}=R.current,vSpan=vE-vS;
      const dx=(e.clientX-drag.x0)/(rr.width-pad-rPad)*vSpan,minG=Math.max(10,vSpan*.01);
      if(drag.type==="C")R.current.setTC(cl(drag.oTC+dx,R.current.tS,R.current.tE));
      else if(drag.type==="L")R.current.setTS(cl(drag.oS+dx,vS,R.current.tE-minG));
      else if(drag.type==="R")R.current.setTE(cl(drag.oE+dx,R.current.tS+minG,vE));
      else if(drag.type==="P"){const sp=drag.oE-drag.oS,ns=cl(drag.oS+dx,vS,vE-sp);R.current.setTS(ns);R.current.setTE(ns+sp)}};
    const up=()=>drag=null;
    const cur=e=>{const t=tAt(e),{tS,tE,tC,vS,vE}=R.current,vSpan=vE-vS,hw=vSpan*.02;
      if(Math.abs(t-tC)<hw+8)cv.style.cursor="col-resize";
      else if(Math.abs(t-tS)<hw+10||Math.abs(t-tE)<hw+10)cv.style.cursor="col-resize";
      else if(t>tS&&t<tE)cv.style.cursor="grab";else cv.style.cursor="default"};
    const wh=e=>{e.preventDefault();const rr=cv.getBoundingClientRect(),fx=(e.clientX-rr.left-pad)/(rr.width-pad-rPad);const{vS,vE,mt}=R.current,vSpan=vE-vS;const f=e.deltaY>0?1.3:1/1.3,ns=cl(vSpan*f,100,mt),ctr=vS+fx*vSpan;const nS=cl(ctr-fx*ns,0,mt-ns);R.current.setVS(nS);R.current.setVE(nS+ns);R.current.setTS(s=>cl(s,nS,nS+ns));R.current.setTE(s=>cl(s,nS+10,nS+ns))};
    cv.addEventListener("pointerdown",dn);cv.addEventListener("pointermove",cur);cv.addEventListener("pointermove",mv);cv.addEventListener("pointerup",up);cv.addEventListener("wheel",wh,{passive:false});
    return()=>{cv.removeEventListener("pointerdown",dn);cv.removeEventListener("pointermove",cur);cv.removeEventListener("pointermove",mv);cv.removeEventListener("pointerup",up);cv.removeEventListener("wheel",wh)}},[!!D]);

  // Phase map cursor drag
  useEffect(()=>{for(const ref of[refs.pmz,refs.pmr]){const cv=ref.current;if(!cv)continue;
    const dn=e=>{const rr=cv.getBoundingClientRect(),fx=(e.clientX-rr.left-34)/(rr.width-38);const{tS,tE}=R.current;R.current.setTC(cl(tS+fx*(tE-tS),tS,tE));cv.setPointerCapture(e.pointerId)};
    const mv=e=>{if(!e.buttons)return;const rr=cv.getBoundingClientRect(),fx=(e.clientX-rr.left-34)/(rr.width-38);const{tS,tE}=R.current;R.current.setTC(cl(tS+fx*(tE-tS),tS,tE))};
    cv.addEventListener("pointerdown",dn);cv.addEventListener("pointermove",mv);
    // Can't cleanly return cleanup for loop, but refs are stable
  }},[!!D]);

  // Cross-section events
  useEffect(()=>{const cv=refs.xs.current;if(!cv)return;let xd=null;
    const pad={l:28,r:8,t:8,b:20};
    const c2p=e=>{const rr=cv.getBoundingClientRect(),{xsH,D}=R.current;if(!D)return{r:0,z:0};const rMax=D.field.r_mm[D.field.r_mm.length-1];return{r:(e.clientX-rr.left-pad.l)/(rr.width-pad.l-pad.r)*rMax,z:xsH-(e.clientY-rr.top-pad.t)/(rr.height-pad.t-pad.b)*2*xsH}};
    const findN=(r,z)=>{const rr=cv.getBoundingClientRect(),{isos,xsH,D}=R.current;if(!D)return-1;const rMax=D.field.r_mm[D.field.r_mm.length-1],rS=(rr.width-pad.l-pad.r)/rMax,zS=(rr.height-pad.t-pad.b)/(2*xsH);let b=-1,bd=1e9;isos.forEach((o,i)=>{const d=Math.sqrt(((o.r-r)*rS)**2+((o.z-z)*zS)**2);if(d<10&&d<bd){bd=d;b=i}});return b};
    const dn=e=>{const p=c2p(e),h=findN(p.r,p.z);if(h>=0){xd={i:h};cv.setPointerCapture(e.pointerId)}else xd=null};
    const mv=e=>{if(!xd)return;const p=c2p(e),{D,pulse}=R.current;const r=+Math.max(0,p.r).toFixed(1),z=+p.z.toFixed(1);R.current.setIsos(prev=>{const n=[...prev];n[xd.i]={...n[xd.i],r,z,n:`r=${r} z=${z}`,t:sim(D,r,z,pulse)};return n})};
    const up=e=>{if(xd){xd=null;return}const p=c2p(e),r=+Math.max(0,p.r).toFixed(1),z=+p.z.toFixed(1),{D:d,pulse:pl,nCI:ci}=R.current;const c=P[ci%P.length];R.current.setNCI(n=>n+1);R.current.setIsos(prev=>[...prev,{r,z,c,v:true,t:sim(d,r,z,pl),n:`r=${r} z=${z}`}])};
    const ctx=e=>{e.preventDefault();const p=c2p(e),h=findN(p.r,p.z);if(h>=0)R.current.setIsos(prev=>prev.filter((_,i)=>i!==h))};
    const wh=e=>{e.preventDefault();R.current.setXsH(h=>cl(h*(e.deltaY>0?.8:1.25),1,125))};
    cv.addEventListener("pointerdown",dn);cv.addEventListener("pointermove",mv);cv.addEventListener("pointerup",up);cv.addEventListener("contextmenu",ctx);cv.addEventListener("wheel",wh,{passive:false});
    return()=>{cv.removeEventListener("pointerdown",dn);cv.removeEventListener("pointermove",mv);cv.removeEventListener("pointerup",up);cv.removeEventListener("contextmenu",ctx);cv.removeEventListener("wheel",wh)}},[!!D]);

  const handleFile=useCallback(e=>{const f=e.target?.files?.[0];if(!f)return;const r=new FileReader();r.onload=ev=>{try{const d=JSON.parse(ev.target.result);setD(d);const mt2=d.field.segments?d.field.segments.reduce((s,seg)=>s+(seg.n_free+seg.n_pulse)*seg.dt*1e6,0):1000;setTS(0);setTE(mt2);setVS(0);setVE(mt2);setTC(mt2);const sc=Object.keys(d.fixed)[0]||"";setScen(sc);if(d.grape){const gi=Object.keys(d.grape).map(Number).sort((a,b)=>a-b);setGIdx(gi.length-1)}const p=d.pulses[sc];const defs=[{r:0,z:0,n:"Centre"},{r:0,z:2,n:"z=2"},{r:0,z:4,n:"z=4 (edge)"},{r:0,z:10,n:"z=10 (out)"},{r:15,z:0,n:"r=15"}];setIsos(defs.map((def,i)=>({r:def.r,z:def.z,c:P[i%P.length],v:true,t:sim(d,def.r,def.z,p),n:def.n})));setNCI(defs.length);setXsH(20)}catch(err){alert("Bad JSON:"+err)}};r.readAsText(f)},[]);

  if(!D)return(<div className="flex items-center justify-center min-h-screen" style={{background:BG}}><label className="cursor-pointer border-2 border-dashed rounded-xl p-16 text-center transition-colors" style={{borderColor:dragOver?AC:GR,color:TX,background:dragOver?"rgba(59,130,246,.08)":"transparent"}} onDragOver={e=>{e.preventDefault();setDragOver(true)}} onDragLeave={()=>setDragOver(false)} onDrop={e=>{e.preventDefault();setDragOver(false);handleFile({target:{files:e.dataTransfer.files}})}}><p className="text-base mb-1">{dragOver?"Release to load":"Drop"} <code className="text-blue-400">bloch_data.json</code> {dragOver?"":"or click"}</p><input type="file" accept=".json" className="hidden" onChange={handleFile}/></label></div>);

  const scenarios=Object.keys(D.fixed);
  const isGrape=scen==="GRAPE"||mode==="grape";

  return(<div className="min-h-screen p-2" style={{background:BG,color:TX,fontFamily:"ui-monospace,monospace",fontSize:12}}>
    {/* Controls */}
    <div className="flex items-center gap-2 mb-2 flex-wrap text-xs">
      <select className="px-2 py-1 rounded border text-xs" style={{background:BG2,borderColor:GR,color:TX}} value={scen} onChange={e=>{setScen(e.target.value);setMode("fixed")}}>{scenarios.map(k=><option key={k}>{k}</option>)}</select>
      {isGrape&&gIters.length>0&&<><input type="range" min={0} max={gIters.length-1} value={gIdx} onChange={e=>setGIdx(+e.target.value)} className="w-32" style={{accentColor:AC}}/><span className="font-bold" style={{color:CUR}}>iter {gIters[gIdx]}</span></>}
      <label className="ml-auto flex items-center gap-1 cursor-pointer" style={{color:TX2}}><input type="checkbox" checked={showMp} onChange={e=>setSMp(e.target.checked)} className="accent-blue-500"/>|M⊥| radius</label>
    </div>
    {/* Main grid: sphere + cross-section */}
    <div className="grid gap-2 mb-1" style={{gridTemplateColumns:"1fr 280px"}}>
      <canvas ref={refs.sphere} className="w-full rounded-lg cursor-grab" style={{height:360,border:`1px solid ${GR}`}}/>
      <div className="flex flex-col gap-1" style={{height:360}}>
        <div className="flex gap-1">
          <input type="range" min={2} max={125} value={xsH} orient="vertical" onChange={e=>setXsH(+e.target.value)} style={{accentColor:AC,writingMode:"vertical-lr",direction:"rtl",width:14,height:240}}/>
          <canvas ref={refs.xs} className="rounded cursor-crosshair" style={{width:"100%",height:240,border:`1px solid ${GR}`}}/>
        </div>
        <p className="text-[8px]" style={{color:TX2}}>click=add · drag=move · r-click=del · scroll=zoom · z ±{xsH}mm</p>
        <div className="flex gap-1"><button className="px-2 py-0.5 rounded text-[9px] border" style={{borderColor:GR,color:TX2}} onClick={()=>{const defs=[{r:0,z:0,n:"Centre"},{r:0,z:2,n:"z=2"},{r:0,z:4,n:"z=4 (edge)"},{r:0,z:10,n:"z=10 (out)"},{r:15,z:0,n:"r=15"}];setIsos(defs.map((d,i)=>({r:d.r,z:d.z,c:P[i%P.length],v:true,t:sim(D,d.r,d.z,pulse),n:d.n})));setNCI(defs.length)}}>Defaults</button><button className="px-2 py-0.5 rounded text-[9px] border" style={{borderColor:GR,color:TX2}} onClick={()=>{setIsos([]);setNCI(0)}}>Clear</button></div>
        <div className="overflow-y-auto" style={{maxHeight:100}}>
          {isos.map((o,i)=><div key={i} className="flex items-center gap-1 py-0.5" style={{borderBottom:`1px solid ${GR}`}}>
            <span className="w-2 h-2 rounded-full flex-shrink-0" style={{background:o.c,opacity:o.v?1:.15}}/>
            <span className="flex-1 truncate text-[9px]" style={{color:TX2}}>{o.n}</span>
            <button className="text-[8px] px-1" style={{color:TX2}} onClick={()=>setIsos(p=>{const n=[...p];n[i]={...n[i],v:!n[i].v};return n})}>{o.v?"hide":"show"}</button>
            <button className="text-[8px] px-1 hover:text-red-400" style={{color:TX2}} onClick={()=>setIsos(p=>p.filter((_,j)=>j!==i))}>×</button>
          </div>)}
        </div>
      </div>
    </div>
    {/* Waveform timeline */}
    <canvas ref={refs.tl} className="w-full rounded mb-1" style={{height:100,border:`1px solid ${GR}`,cursor:"default"}}/>
    {/* Phase heatmaps */}
    <div className="grid gap-1 mb-1" style={{gridTemplateColumns:"1fr 1fr"}}>
      <canvas ref={refs.pmz} className="w-full rounded cursor-col-resize" style={{height:110,border:`1px solid ${GR}`}}/>
      <canvas ref={refs.pmr} className="w-full rounded cursor-col-resize" style={{height:110,border:`1px solid ${GR}`}}/>
    </div>
    {/* Angle plots */}
    <canvas ref={refs.plots} className="w-full rounded" style={{height:150}}/>
    <div className="flex items-center gap-3 mt-1 text-[9px]" style={{color:TX2}}>
      <span>Scroll timeline to zoom · drag <span style={{color:AC}}>blue</span> handles for time window · drag <span style={{color:CUR}}>gold</span> cursor on phase maps · cross-section shaded at cursor time</span>
    </div>
  </div>);
}