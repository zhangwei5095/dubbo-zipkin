package com.threeq.dubbo.tracing;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanExtractor;
import org.springframework.cloud.sleuth.SpanInjector;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.sampler.NeverSampler;

import java.util.Map;

/**
 * @Date 2017/2/8
 * @User three
 */
@Activate(group = {Constants.PROVIDER}, order = -9000)
public class ProviderSpanFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        boolean isTraceDubbo = false;
        Tracer tracer = null;
        SpanExtractor spanExtractor = null;
        SpanInjector spanInjector = null;
        SpanReporter spanReporter = null;
        Span span = null;
        try {
            Map<String, String> attachments = RpcContext.getContext().getAttachments();
            tracer = ApplicationContextAwareBean.CONTEXT.getBean(Tracer.class);
            spanExtractor = ApplicationContextAwareBean.CONTEXT.getBean(DubboSpanExtractor.class);
            spanInjector = ApplicationContextAwareBean.CONTEXT.getBean(DubboSpanInjector.class);
            spanReporter = ApplicationContextAwareBean.CONTEXT.getBean(SpanReporter.class);

            isTraceDubbo = (tracer != null && spanExtractor != null && spanInjector != null && spanReporter != null);
            if (isTraceDubbo) {
                String spanName = invoker.getUrl().getParameter("interface") + ":" + invocation.getMethodName() + ":" + invoker.getUrl().getParameter("version") + "(" + invoker.getUrl().getHost() + ")";
                Span parent = spanExtractor
                        .joinTrace(RpcContext.getContext());
                boolean skip = Span.SPAN_NOT_SAMPLED.equals(attachments.get(Span.SAMPLED_NAME));
                if (parent != null) {
                    span = tracer.createSpan(spanName, parent);
                    if (parent.isRemote()) {
                        parent.logEvent(Span.SERVER_RECV);
                    }
                } else {
                    if (skip) {
                        span = tracer.createSpan(spanName, NeverSampler.INSTANCE);
                    } else {
                        span = tracer.createSpan(spanName);
                    }
                    span.logEvent(Span.SERVER_RECV);
                }

                spanInjector.inject(span, RpcContext.getContext());
            }
            Result result = invoker.invoke(invocation);
            return result;


        } finally {
            if (isTraceDubbo && span != null) {
                if (span.hasSavedSpan()) {
                    Span parent = span.getSavedSpan();
                    if (parent.isRemote()) {
                        parent.logEvent(Span.SERVER_SEND);
                        parent.stop();
                        spanReporter.report(parent);
                    }
                } else {
                    span.logEvent(Span.SERVER_SEND);
                }
                tracer.close(span);
            }
        }

    }

}
