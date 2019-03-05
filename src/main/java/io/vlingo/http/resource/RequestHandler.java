/*
 * Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL
 * was not distributed with this file, You can obtain
 * one at https://mozilla.org/MPL/2.0/.
 */

package io.vlingo.http.resource;

import io.vlingo.common.Completes;
import io.vlingo.common.Outcome;
import io.vlingo.common.Success;
import io.vlingo.http.Method;
import io.vlingo.http.Request;
import io.vlingo.http.Response;
import io.vlingo.http.ResponseError;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class RequestHandler<T extends Response> {
  final private Pattern pattern = Pattern.compile("\\{(.*?)\\}");

  public final Method method;
  public final String path;
  public final String actionSignature;

  RequestHandler(final Method method, final String path, final List<ParameterResolver<?>> parameterResolvers) {
    this.method = method;
    this.path = path;
    this.actionSignature = generateActionSignature(parameterResolvers);
  }

  Completes<Response> defaultErrorResponse() {
    return Completes.withSuccess(Response.of(Response.Status.InternalServerError));
  }

  protected void checkHandlerOrThrowException(Object handler) {
    if (handler == null)
      throw new HandlerMissingException("No handle defined for " + method.toString() + " " + path);
  }

  abstract Completes<Outcome<ResponseError, T>> execute(final Request request,
                                                       final Action.MappedParameters mappedParameters);

  Completes<Outcome<ResponseError, T>> executeRequest(Supplier<Completes<T>> executeAction, ErrorHandler errorHandler) {
    try {
      return executeAction.get()
        .andThen(Success::of); //TODO: wrap failure to ResponseError?
    } catch(Exception exception) {
      // Log failure at DEBUG level (missing logger)
      if (errorHandler != null) {
        try {
          return errorHandler.handle(exception).andThen(ResponseError::asFailure);
        } catch (Exception errorHandlerException) {
          // Log failure at ERROR level (missing logger)
          return defaultErrorResponse().andThen(ResponseError::asFailure);
        }
      } else {
        return defaultErrorResponse().andThen(ResponseError::asFailure);
      }
    }
  }


  private String generateActionSignature(final List<ParameterResolver<?>> parameterResolvers) {
    checkOrder(parameterResolvers);

    if (path.replaceAll(" ", "").contains("{}")) {
      throw new IllegalArgumentException("Empty path parameter name for " + method + " " + path);
    }

    final StringBuilder result = new StringBuilder();
    final Matcher matcher = pattern.matcher(path);
    boolean first = true;
    for (ParameterResolver<?> resolver: parameterResolvers) {
      if(resolver.type == ParameterResolver.Type.PATH) {
        matcher.find();
        if (first) {
          first = false;
        } else {
          result.append(", ");
        }
        result.append(resolver.paramClass.getSimpleName()).append(" ").append(matcher.group(1));
      }
    }
    return result.toString();
  }

  private void checkOrder(final List<ParameterResolver<?>> parameterResolvers) {
    boolean firstNonPathResolver = false;
    for(ParameterResolver<?> resolver: parameterResolvers) {
      if(resolver.type != ParameterResolver.Type.PATH) {
        firstNonPathResolver = true;
      }
      if(firstNonPathResolver && resolver.type == ParameterResolver.Type.PATH) {
        throw new IllegalArgumentException("Path parameters are unsorted");
      }
    }
  }

  protected Mapper mapperFrom(final Class<? extends Mapper> mapperClass) {
    try {
      return mapperClass.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot instantiate mapper class: " + mapperClass.getName());
    }
  }
}
