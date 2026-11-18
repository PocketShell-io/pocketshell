package com.pocketshell.app.smoke;

import org.json.JSONObject;

/**
 * #3154: JS snippets that make a journey act on, and wait for, UI a user can actually
 * see and tap. A bare {@code node.click()} works on a {@code display:none} element and a
 * presence-only wait passes on a 0x0 node, so a journey could go green while the screen
 * showed something else (J27 opened the New session dialog inside the collapsed phone
 * session panel). Journeys click through {@link #click} and wait through {@link #visible}.
 */
public final class JsVisibility {
    private JsVisibility() {}

    /**
     * JS statements that make the enclosing function return 'FAILED: <reason>' unless {@code nodeVar} (a JS variable holding an element) is rendered with a
     * non-zero box and the topmost element at its centre is inside it. Scrolls it into view first, as a user would.
     */
    private static String guard(String nodeVar, String what) {
        return "if(!" + nodeVar + ")return 'FAILED: missing '+" + what + ";"
                + "if(" + nodeVar + ".scrollIntoView)" + nodeVar + ".scrollIntoView({block:'center',inline:'center'});"
                + "{const r=" + nodeVar + ".getBoundingClientRect();"
                + "if(getComputedStyle(" + nodeVar + ").display==='none'||r.width<=0||r.height<=0)"
                + "return 'FAILED: hidden (0x0 or display:none), not tappable: '+" + what + ";"
                + "const h=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "if(!h||!" + nodeVar + ".contains(h))"
                + "return 'FAILED: covered, not tappable: '+" + what
                + "+' (top element: '+(h?h.tagName+'.'+h.className:'none')+'; rect='+[r.left,r.top,r.width,r.height].map(Math.round)"
                + "+'; viewport='+innerWidth+'x'+innerHeight+'; ancestors='+(()=>{const o=[];for(let a=" + nodeVar + ".parentElement;a&&o.length<8;a=a.parentElement){"
                + "const c=getComputedStyle(a),q=a.getBoundingClientRect();o.push(a.tagName.toLowerCase()+'.'+String(a.className).split(' ')[0]+'['+c.display+'|'+c.position+'|'+c.transform+'|'+Math.round(q.left)+','+Math.round(q.width)+']');}return o.join('>');})()+')';}";
    }

    /** JS statements that guard then click the element held in {@code nodeVar}. */
    public static String clickNode(String nodeVar, String what) {
        return guard(nodeVar, JSONObject.quote(what)) + nodeVar + ".click();";
    }

    /** JS expression (an IIFE returning 'clicked') that guards then clicks {@code nodeExpr}; a missing node throws. */
    public static String click(String nodeExpr) {
        return "(()=>{try{const __n=" + nodeExpr + ";" + clickNode("__n", nodeExpr)
                + "return 'clicked';}catch(e){return 'FAILED: '+e.message;}})()";
    }

    /** Like {@link #click} but a missing node is a no-op ('absent'); a present-but-hidden node still throws. */
    public static String clickIfPresent(String nodeExpr) {
        return "(()=>{try{const __n=" + nodeExpr + ";if(!__n)return 'absent';" + clickNode("__n", nodeExpr)
                + "return 'clicked';}catch(e){return 'FAILED: '+e.message;}})()";
    }

    /** Throws when a raw evaluateJavascript result is a guard failure ('FAILED: ...'), keeping the page-side reason. */
    public static String failOnGuard(String raw) {
        if (raw != null && raw.startsWith("\"FAILED: ")) throw new AssertionError(raw);
        return raw;
    }

    /** Fails the journey with the page-side reason when a {@link #click} result is not a click. */
    public static String expectClicked(String result) {
        if (result != null && result.startsWith("FAILED: ")) throw new AssertionError(result);
        return result;
    }

    /** JS boolean expression: {@code selector} matches an element that is rendered, non-zero sized and topmost at its centre. */
    public static String visible(String selector) {
        return visibleNode("document.querySelector(" + JSONObject.quote(selector) + ")");
    }

    /** JS boolean expression for an arbitrary node expression (hit-tested when its centre is inside the viewport). */
    public static String visibleNode(String nodeExpr) {
        return "(()=>{const __n=" + nodeExpr + ";if(!__n)return false;"
                + "const r=__n.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;"
                + "if(getComputedStyle(__n).display==='none')return false;"
                + "const x=r.left+r.width/2,y=r.top+r.height/2;"
                + "if(x<0||y<0||x>innerWidth||y>innerHeight)return true;"
                + "const h=document.elementFromPoint(x,y);return !!h&&__n.contains(h);})()";
    }
}
