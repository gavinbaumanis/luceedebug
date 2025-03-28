package luceedebug.coreinject.frame;

import lucee.runtime.PageContext;
import lucee.runtime.PageContextImpl;
import lucee.runtime.type.scope.LocalNotSupportedScope;

import lucee.runtime.type.Collection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.google.common.collect.MapMaker;

import luceedebug.*;
import luceedebug.coreinject.CfValueDebuggerBridge;
import luceedebug.coreinject.ClosureScopeLocalScopeAccessorShim;
import luceedebug.coreinject.DebugEntity;
import luceedebug.coreinject.UnsafeUtils;
import luceedebug.coreinject.ValTracker;
import luceedebug.coreinject.CfValueDebuggerBridge.MarkerTrait;

public class Frame extends DebugFrame {
    static private AtomicLong nextId = new AtomicLong(0);

    /**
     * It's not 100% clear that our instrumentation to walk captured closure scopes will always be valid across all class loaders,
     * and we assume that if it fails once, we should disable it across the entire program.
     */
    static private boolean closureScopeGloballyDisabled = false;

    public final ValTracker valTracker;

    final private FrameContext frameContext_;
    final private String sourceFilePath;
    final private long id;
    final private String name;
    final private int depth; // 0 is first frame in stack, 1 is next, ...
    private int line = 0; // initially unknown, until first step notification
    
    /**
     * True if this cf frame's actual java method is "udfDefaultValue", see lucee source
     * This should be final and init'd via constructor but it's not a pressing issue.
     */
    public boolean isUdfDefaultValueInitFrame = false;

    public String getSourceFilePath() { return sourceFilePath; };
    public long getId() { return id; }
    public String getName() { return name; }
    public int getDepth() { return depth; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    // lazy initialized on request for scopes
    // This is "scopes, wrapped with trackable IDs, which are expensive to create and cleanup"
    private LinkedHashMap<String, CfValueDebuggerBridge> scopes_ = null;

    // the results of evaluating complex expressions need to be kept alive for the entirety of the frame
    // these should be made gc'able when this frame is collected
    // We might want to place these results somewhere that is kept alive for the whole request?
    private ArrayList<Object> refsToKeepAlive_ = new ArrayList<>();
    void pin(Object obj) {
        refsToKeepAlive_.add(obj);
    }

    // hold strong refs to scopes, because PageContext will swap them out as frames change (variables, local, this)
    // (application, server and etc. maybe could be held as globals)
    // We don't want to construct tracked refs to them until a debugger asks for them, because it is expensive
    // to create and clean up references for every pushed frame, especially if that frame isn't ever inspected in a debugger.
    // This should be valid for the entirety of the frame, and should the frame should be always be disposed of at the end of the actual cf frame.
    //
    // lifetime: we shouldn't hold onto a frame longer than the engine holds onto the "frame"
    // (where frame there is in air quotes because the engine doesn't track explicit frames)
    // We want to not make this un-GC'able at the time the engine assumes it's GC'able.
    // This should be doable by virtue of our frames being popped and released immediately before
    // the engine is truly do with its "frame". Fallback here would be use a WeakRef<> but it doesn't
    // seem necessary.
    //
    public static class FrameContext {
        final public PageContext pageContext;

        public final lucee.runtime.type.scope.Scope application;
        public final lucee.runtime.type.scope.Argument arguments;
        public final lucee.runtime.type.scope.Scope form;
        public final lucee.runtime.type.scope.Local local;
        public final lucee.runtime.type.scope.Scope request;
        public final lucee.runtime.type.scope.Scope session;
        public final lucee.runtime.type.scope.Scope server;
        public final lucee.runtime.type.scope.Scope url;
        public final lucee.runtime.type.scope.Variables variables;
        // n.b. the `this` scope does not derive from Scope
        public final lucee.runtime.type.Struct this_;
        public final lucee.runtime.type.scope.Scope static_;
        
        // lazy init because it (might?) be expensive to walk scope chains eagerly every frame
        private ArrayList<lucee.runtime.type.scope.ClosureScope> capturedScopeChain = null;

        static private final ConcurrentMap<PageContext, Object> activeFrameLockByPageContext = new MapMaker()
            .weakKeys()
            .makeMap();

        //
        // Note that some of these `getScopeOrNull` calls need additional guards, to prevent from throwing
        // expensive exceptions on literally every frame, e.g. if a scope is disabled by the engine and trying to touch it
        // throws an ExpressionException.
        //
        private FrameContext(PageContext pageContext, FrameContext root) {
            this.pageContext = pageContext;
            this.application = root != null ? root.application : getScopelikeOrNull(() -> pageContext.applicationScope());
            this.arguments   = getScopelikeOrNull(() -> pageContext.argumentsScope());
            this.form        = root != null ? root.form : getScopelikeOrNull(() -> pageContext.formScope());
            this.local       = getScopelikeOrNull(() -> pageContext.localScope());
            this.request     = root != null ? root.request : getScopelikeOrNull(() -> pageContext.requestScope());
            this.session     = root != null ? root.session : getScopelikeOrNull(() -> pageContext.getApplicationContext().isSetSessionManagement() ? pageContext.sessionScope() : null);
            this.server      = root != null ? root.server : getScopelikeOrNull(() -> pageContext.serverScope());
            this.url         = root != null ? root.url : getScopelikeOrNull(() -> pageContext.urlScope());
            this.variables   = getScopelikeOrNull(() -> pageContext.variablesScope());
            this.this_       = getScopelikeOrNull(() -> {
                // there is also `PageContextImpl.thisGet()` but it can create a `this` property on the variables scope, which seems like
                // something we don't want to do, since it mutates the user's scopes instead of just reading from them.
                if (this.variables instanceof lucee.runtime.ComponentScope) {
                    // The `this` scope IS the component, bound to the variables scope that is an instanceof ComponentScope
                    // (which means ComponentScope is a variables scope containing a THIS scope, rather than ComponentScope IS the this scope)
                    // Alternatively we could just lookup the `this` property on `variables`.
                    return ((lucee.runtime.ComponentScope)this.variables).getComponent();
                }
                else if (this.variables instanceof lucee.runtime.type.scope.ClosureScope) {
                    // A closure scope is a variables scope wrapper containing a variable scope.
                    // Probably we could test here for if the closureScope contains a component scope, but just looking for `this` seems to be fine.
                    return (lucee.runtime.type.Struct)UnsafeUtils.deprecatedScopeGet(this.variables, "this");
                }
                else {
                    return null;
                }
            });

            // If we have a `this` scope, meaning we are in a component, then we should have a static scope.
            this.static_ = this.this_ instanceof lucee.runtime.Component ? ((lucee.runtime.Component)this.this_).staticScope() : null;
        }

        public ArrayList<lucee.runtime.type.scope.ClosureScope> getCapturedScopeChain() {
            if (capturedScopeChain == null) {
                capturedScopeChain = getCapturedScopeChain(variables);
            }
            return capturedScopeChain;
        }

        private static ArrayList<lucee.runtime.type.scope.ClosureScope> getCapturedScopeChain(lucee.runtime.type.scope.Scope variables) {
            if (variables instanceof lucee.runtime.type.scope.ClosureScope) {
                final var setLike_seen = new IdentityHashMap<>();
                final var result = new ArrayList<lucee.runtime.type.scope.ClosureScope>();
                var scope = variables;
                while (scope instanceof lucee.runtime.type.scope.ClosureScope) {
                    final var captured = (lucee.runtime.type.scope.ClosureScope)scope;
                    if (setLike_seen.containsKey(captured)) {
                        break;
                    }
                    else {
                        setLike_seen.put(captured, true);
                    }
                    result.add(captured);
                    scope = captured.getVariables();
                }
                return result;
            }
            else {
                return new ArrayList<>();
            }
        }

        interface SupplierOrNull<T> {
            T get() throws Throwable;
        }

        // sometimes trying to get a scope throws, in which case we get null
        // scopes that are "garbage" scopes ("LocalNotSupportedScope") should be filtered away elsewhere
        // we especially are interested in when we swap out scopes during expression evaluation that we restore the scopes
        // as they were prior to; which might be troublesome if "getting a scope throws so we return null, but it doesn't make sense to restore the scope to null"
        private <T> T getScopelikeOrNull(SupplierOrNull<T> f) {
            try {
                return f.get();
            }
            catch(Throwable e) {
                return null;
            }
        }

        // if we're mutating some page context's frame information in place, we should only do it on one thread at a time.
        static private <T> T withPageContextLock(PageContext pageContext, Supplier<T> f) {
            // lazy create the lock if it doesn't exist yet
            synchronized(activeFrameLockByPageContext.computeIfAbsent(pageContext, (_x) -> new Object())) {
                return f.get();
            }
        }

        /**
         * "real" frames are swapped-out in place inside the engine, so there's just one page context that has its 
         * current context mutated on function enter/exit. To evaluate an expression inside of some frame context,
         * we need to replace the page context's relevant scopes with the ones for "this" frame, perform the evaluation,
         * and then restore everything we swapped out.
         */
        public <T> T doWorkInThisFrame(Supplier<T> f)  {
            return withPageContextLock(pageContext, () -> {
                final var saved_argumentsScope = getScopelikeOrNull(() -> pageContext.argumentsScope());
                final var saved_localScope = getScopelikeOrNull(() -> pageContext.localScope());
                final var saved_variablesScope = getScopelikeOrNull(() -> pageContext.variablesScope());
                try {
                    pageContext.setFunctionScopes(local, arguments);
                    pageContext.setVariablesScope(variables);
                    return f.get();
                }
                finally {
                    pageContext.setVariablesScope(saved_variablesScope);
                    pageContext.setFunctionScopes(saved_localScope, saved_argumentsScope);
                }
            });
        }
    }

    Frame(String sourceFilePath, int depth, ValTracker valTracker, PageContext pageContext, FrameContext root) {
        this(sourceFilePath, depth, valTracker, pageContext, Frame.tryGetFrameName(pageContext), root);
    }

    private Frame(String sourceFilePath, int depth, ValTracker valTracker, PageContext pageContext, String name, FrameContext root) {
        this.frameContext_ = new FrameContext(pageContext, root);
        this.sourceFilePath = Objects.requireNonNull(sourceFilePath);
        this.valTracker = Objects.requireNonNull(valTracker);
        this.id = nextId.incrementAndGet();
        this.name = name;
        this.depth = depth;
    }

    private static String tryGetFrameName(PageContext pageContext) {
        String frameName = "??";
        try {
            final PageContextImpl pageContextImpl = (PageContextImpl)pageContext;
            final Collection.Key key = pageContextImpl.getActiveUDFCalledName();
            if (key != null) {
                frameName = key.getString();
            }
        }
        catch (Throwable e) {
            // discard, cast was bad for some reason?
        }
        return frameName;
    }

    private void checkedPutScopeRef(String name, Map<?,?> scope) {
        if (scope != null && !(scope instanceof LocalNotSupportedScope)) {
            var v = new MarkerTrait.Scope(scope);
            pin(v);
            scopes_.put(name, new CfValueDebuggerBridge(this, v));
        }
    }

    private void lazyInitScopeRefs() {
        if (scopes_ != null) {
            // already init'd
            return;
        }

        scopes_ = new LinkedHashMap<>();
        checkedPutScopeRef("application", frameContext_.application);
        checkedPutScopeRef("arguments", frameContext_.arguments);
        checkedPutScopeRef("form", frameContext_.form);
        checkedPutScopeRef("local", frameContext_.local);
        checkedPutScopeRef("request", frameContext_.request);
        checkedPutScopeRef("session", frameContext_.session);
        checkedPutScopeRef("static", frameContext_.static_);
        checkedPutScopeRef("server", frameContext_.server);
        checkedPutScopeRef("this", frameContext_.this_);
        checkedPutScopeRef("url", frameContext_.url);
        checkedPutScopeRef("variables", frameContext_.variables);

        if (!closureScopeGloballyDisabled) {
            final var scopeChain = frameContext_.getCapturedScopeChain();
            final int captureChainLen = scopeChain.size();
            try {
                for (int i = 0; i < captureChainLen; i++) {
                    // this should always succeed, there's no casting into a luceedebug shim type
                    checkedPutScopeRef("captured arguments " + i, scopeChain.get(i).getArgument());
                    // this could potentially fail with a class cast exception
                    checkedPutScopeRef("captured local " + i, ((ClosureScopeLocalScopeAccessorShim)scopeChain.get(i)).getLocalScope());
                }
            }
            catch (ClassCastException e) {
                // We'll be left with possibly some capture scopes in the list this time around,
                // but all subsequent calls to this method will be guarded by this assignment.
                closureScopeGloballyDisabled = true;
                return;
            }
        }
    }

    /**
     * for debugger-internal use, e.g. in watch expressions
     */
    public FrameContext getFrameContext() {
        return frameContext_;
    }

    /**
     * for direct DAP use
     */
    public IDebugEntity[] getScopes() {
        lazyInitScopeRefs();
        IDebugEntity[] result = new DebugEntity[scopes_.size()];
        int i = 0;
        for (var kv : scopes_.entrySet()) {
            String name = kv.getKey();
            CfValueDebuggerBridge entityRef = kv.getValue();
            var entity = new DebugEntity();
            entity.name = name;
            entity.namedVariables = entityRef.getNamedVariablesCount();
            entity.indexedVariables = entityRef.getIndexedVariablesCount();
            entity.expensive = true;
            entity.variablesReference = entityRef.id;
            result[i] = entity;
            i += 1;
        }
        return result;
    }

    public CfValueDebuggerBridge trackEvalResult(Object obj) {
        var v = new CfValueDebuggerBridge(this, obj);
        CfValueDebuggerBridge.pin(obj);
        return v;
    }
}
