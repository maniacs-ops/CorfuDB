package org.corfudb.router;

import io.netty.channel.ChannelHandlerContext;

import java.lang.annotation.Annotation;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Created by mwei on 11/23/16.
 */
public class ClientMsgHandler<M extends IRoutableMsg<T>, T> {

    public interface Handler<M> {
        M handle(M CorfuMsg, IChannel<M> channel);
    }

    public interface VoidHandler<M> {
        void handle(M msg, IChannel<M> channel);
    }

    /** The handler map. */
    private Map<T, Handler<M>> handlerMap;

    /** The client. */
    private AbstractClient client;

    /** Construct a new instance of ClientMsgHandler. */
    public ClientMsgHandler(AbstractClient client) {
        this.client = client;
        handlerMap = new ConcurrentHashMap<>();
    }

    /** Add a handler to this message handler.
     *
     * @param messageType       The type of CorfuMsg this handler will handle.
     * @param handler           The handler itself.
     * @return                  This handler, to support chaining.
     */
    public ClientMsgHandler<M,T>
    addHandler(T messageType, ClientMsgHandler.Handler<M> handler) {
        handlerMap.put(messageType, handler);
        return this;
    }

    /** Handle an incoming message.
     *
     * @param message   The message to handle.
     * @param channel   The channel handler context.
     * @return          True, if the message was handled.
     *                  False otherwise.
     */
    @SuppressWarnings("unchecked")
    public boolean handle(M message, IChannel<M> channel) {
        if (handlerMap.containsKey(message.getMsgType())) {
            handlerMap.get(message.getMsgType()).handle(message, channel);
            return true;
        }
        return false;
    }


    /** Generate handlers for a particular client.
     *
     * @param caller    The context that is being used. Call MethodHandles.lookup() to obtain.
     * @param o         The object that implements the client.
     * @return
     */
    public <A extends Annotation> ClientMsgHandler<M,T>
    generateHandlers(final MethodHandles.Lookup caller, final Object o, Class<A> annotationType,
                      Function<A,T> typeFromAnnoationFn) {
        Arrays.stream(o.getClass().getDeclaredMethods())
                .filter(x -> x.isAnnotationPresent(annotationType))
                .forEach(x -> {
                    A a = x.getAnnotation(annotationType);
                    T type = typeFromAnnoationFn.apply(a);
                    // convert the method into a Java8 Lambda for maximum execution speed...
                    try {
                        if (Modifier.isStatic(x.getModifiers())) {
                            MethodHandle mh = caller.unreflect(x);
                            MethodType mt = mh.type().changeParameterType(0, Object.class);
                            if (mt.returnType().equals(Void.TYPE)) {
                                VoidHandler<M> vh = (VoidHandler<M>) LambdaMetafactory.metafactory(caller,
                                        "handle", MethodType.methodType(VoidHandler.class),
                                        mt, mh, mh.type())
                                        .getTarget().invokeExact();
                                handlerMap.put(type, (M msg, IChannel<M> ctx) -> {
                                    vh.handle(msg, ctx);
                                    return null;
                                });
                            } else {
                                mt = mt.changeReturnType(Object.class);
                                handlerMap.put(type, (Handler) LambdaMetafactory.metafactory(caller,
                                        "handle", MethodType.methodType(Handler.class),
                                        mt, mh, mh.type())
                                        .getTarget().invokeExact());
                            }
                        } else {
                            // instance method, so we need to capture the type.
                            MethodType mt = MethodType.methodType(x.getReturnType(), x.getParameterTypes());
                            MethodHandle mh = caller.findVirtual(o.getClass(), x.getName(), mt);
                            MethodType mtGeneric = mh.type().changeParameterType(1, Object.class);
                            if (mt.returnType().equals(Void.TYPE)) {
                                VoidHandler<M> vh = (VoidHandler<M>) LambdaMetafactory.metafactory(caller,
                                        "handle", MethodType.methodType(VoidHandler.class, o.getClass()),
                                        mtGeneric.dropParameterTypes(0,1), mh, mh.type().dropParameterTypes(0,1))
                                        .getTarget().bindTo(o).invoke();
                                handlerMap.put(type, (M msg, IChannel<M> ctx) -> {
                                    vh.handle(msg, ctx);
                                    return null;
                                });
                            } else {
                                mt = mt.changeReturnType(Object.class);
                                mtGeneric = mtGeneric.changeReturnType(Object.class);
                                handlerMap.put(type, (Handler) LambdaMetafactory.metafactory(caller,
                                        "handle", MethodType.methodType(Handler.class, o.getClass()),
                                        mtGeneric.dropParameterTypes(0,1), mh, mh.type().dropParameterTypes(0,1))
                                        .getTarget().bindTo(o).invoke());
                            }
                        }
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });
        return this;
    }
    /** Get the types this handler will handle.
     *
     * @return  The types this handler will handle.
     */
    public Set<T> getHandledTypes() {
        return handlerMap.keySet();
    }
}
