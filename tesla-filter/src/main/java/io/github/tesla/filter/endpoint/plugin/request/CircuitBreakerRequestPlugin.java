package io.github.tesla.filter.endpoint.plugin.request;

import java.util.concurrent.TimeUnit;

import com.hazelcast.core.IMap;

import io.github.tesla.filter.AbstractRequestPlugin;
import io.github.tesla.filter.endpoint.definition.CircuitBreakerDefinition;
import io.github.tesla.filter.support.annnotation.EndpointRequestPlugin;
import io.github.tesla.filter.support.circuitbreaker.CircuitBreaker;
import io.github.tesla.filter.support.circuitbreaker.HazelCastCircuitBreaker;
import io.github.tesla.filter.support.servlet.NettyHttpServletRequest;
import io.github.tesla.filter.utils.JsonUtils;
import io.github.tesla.filter.utils.PluginUtil;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;

@EndpointRequestPlugin(filterType = "CircuitBreakerRequestPlugin", definitionClazz = CircuitBreakerDefinition.class,
    filterOrder = 13, filterName = "熔断插件")
public class CircuitBreakerRequestPlugin extends AbstractRequestPlugin {

    private static IMap<String, CircuitBreaker> CIRCUITBREAKER_CACHE;

    @Override
    public HttpResponse doFilter(NettyHttpServletRequest servletRequest, HttpObject realHttpObject,
        Object filterParam) {
        CircuitBreakerDefinition definition = JsonUtils.json2Definition(filterParam, CircuitBreakerDefinition.class);
        if (definition == null) {
            return null;
        }
        if (CIRCUITBREAKER_CACHE == null) {
            CIRCUITBREAKER_CACHE = getHazelcastInstance().getMap("circuitBreakerMap");
        }
        String uri = servletRequest.getRequestURI();
        CircuitBreaker circuitBreaker = null;
        final String failRateForClose = definition.getFailRateForClose();
        final int idleTimeForOpen = definition.getIdleTimeForOpen();
        final String passRateForHalfOpen = definition.getPassRateForHalfOpen();
        final int failNumForHalfOpen = definition.getFailNumForHalfOpen();
        if (CIRCUITBREAKER_CACHE.containsKey(uri)) {
            circuitBreaker = CIRCUITBREAKER_CACHE.get(uri);
            circuitBreaker.reset(failRateForClose, idleTimeForOpen, passRateForHalfOpen, failNumForHalfOpen);
        } else {
            // 熔断只一天生效？ 如果永久不失效的话，hazelcast的内存怎么清理？有一些问题
            circuitBreaker =
                new HazelCastCircuitBreaker(failRateForClose, idleTimeForOpen, passRateForHalfOpen, failNumForHalfOpen);
            CIRCUITBREAKER_CACHE.put(uri, circuitBreaker, 1, TimeUnit.DAYS);
        }
        servletRequest.setAttribute("_CircuitBreaker", circuitBreaker);
        if (circuitBreaker.canPassCheck()) {
            return null;
        } else {
            // 被熔断了，返回定义Mock数据出去
            HttpResponse response = PluginUtil.createResponse(HttpResponseStatus.OK, servletRequest.getNettyRequest(),
                definition.getFallback());
            HttpUtil.setKeepAlive(response, false);
            return response;
        }
    }

}
