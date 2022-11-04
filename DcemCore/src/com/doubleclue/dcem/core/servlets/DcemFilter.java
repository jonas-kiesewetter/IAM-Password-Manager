package com.doubleclue.dcem.core.servlets;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.spongycastle.crypto.io.InvalidCipherTextIOException;

import com.doubleclue.dcem.core.DcemConstants;
import com.doubleclue.dcem.core.cluster.DcemCluster;
import com.doubleclue.dcem.core.entities.DcemUser;
import com.doubleclue.dcem.core.entities.TenantEntity;
import com.doubleclue.dcem.core.exceptions.DcemException;
import com.doubleclue.dcem.core.gui.DcemApplicationBean;
import com.doubleclue.dcem.core.jpa.TenantIdResolver;
import com.doubleclue.dcem.core.logic.CookieHelper;
import com.doubleclue.dcem.core.logic.DomainAzure;
import com.doubleclue.dcem.core.logic.DomainLogic;
import com.doubleclue.dcem.core.logic.OperatorSessionBean;
import com.doubleclue.dcem.core.logic.UserLogic;

public abstract class DcemFilter implements Filter {

	@Inject
	protected DcemApplicationBean applicationBean;

	@Inject
	protected DomainLogic domainLogic;

	@Inject
	UserLogic userLogic;

	private static final Logger logger = LogManager.getLogger(DcemFilter.class);

	private static final String FACES_REQUEST = "Faces-Request";
	private static final String FACES_AJAX_REQUEST = "partial/ajax";
	private static final String OPEN_SUFFIX = "_.xhtml";

	protected boolean enabled = true;

	protected int webPort;
	protected String path;

	protected String webName;
	protected String welcomePage;
	protected List<String> allowedPaths;
	protected String redirectionPage;
	protected String redirectPort;

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	abstract public boolean isLoggedin(HttpServletRequest httpServletRequest);

	abstract public String getUserId();

	abstract public void setTenant(TenantEntity tenantEntity);

	abstract public void logUserIn(DcemUser dcmeUser, HttpServletRequest httpServletRequest) throws Exception;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		allowedPaths = new ArrayList<>();
		redirectPort = DcemCluster.getDcemCluster().getClusterConfig().getRedirectPort80();
		if (redirectPort != null && redirectPort.isEmpty() == false) {
			if (redirectPort.equals("443")) {
				redirectPort = "";
			} else {
				redirectPort = ":" + redirectPort;
			}
		} else {
			redirectPort = null;
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

		HttpServletRequest httpServletRequest = (HttpServletRequest) request;
		HttpServletResponse httpServletResponse = (HttpServletResponse) response;
		path = httpServletRequest.getServletPath();
		// System.out.println("DcemFilter.doFilter() Path: " + path + "  WebName: " + webName);
		if (isSessionControlRequiredForThisResource(httpServletRequest)) {
			if (isSessionInvalid(httpServletRequest)) {
				String redirectUrl = httpServletRequest.getContextPath() + webName + "/" + DcemConstants.EXPIRED_PAGE;
				if (isAjaxRequest(httpServletRequest)) {
					response.setContentType("text/xml");
					response.getWriter().append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
					response.getWriter().append("<partial-response><redirect url=\"");
					response.getWriter().append(redirectUrl);
					response.getWriter().append("\"></redirect></partial-response>");
				} else {
					if (httpServletRequest.getHeader("authorization") == null) {
						httpServletRequest.getSession().invalidate();
						if (path.endsWith(OPEN_SUFFIX) || allowedPaths.contains(path)) { // excemption
							chain.doFilter(request, response);
							return;
						} else {
							httpServletResponse.sendRedirect(redirectUrl);
						}
						return;
					}
				}
			}
		}

		int remotePort = request.getLocalPort();
		if (remotePort == 80 && redirectPort != null) {
			URL url = new URL(httpServletRequest.getRequestURL().toString());
			httpServletResponse.sendRedirect("https://" + url.getHost() + redirectPort + url.getPath());
			return;
		}
		if (remotePort != webPort) {
			logger.warn("Wrong Port. WebPort=" + webPort + " RemotePort=" + remotePort + ", remoteAddress=" + request.getRemoteAddr());
			httpServletResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		try {
			// checkIfViewAllowed(httpServletRequest);
			setTenant((TenantEntity) httpServletRequest.getSession().getAttribute(DcemConstants.URL_TENANT_PARAMETER));
			boolean loggedIn = false;
			try {
				loggedIn = isLoggedin(httpServletRequest);
			} catch (Exception e) {
				logger.warn("isUserLoggedin", e);
				httpServletRequest.getSession().invalidate();
			}
			if (loggedIn == true) {
				if (path.equals(webName)) {
					redirect(httpServletRequest, response, path + "/" + welcomePage, false);
					return;
				}
				ThreadContext.put(DcemConstants.MDC_USER_ID, getUserId());
				chain.doFilter(request, response);
				return;
			}
			ThreadContext.put(DcemConstants.MDC_USER_ID, "");
			TenantEntity tenantEntity = (TenantEntity) httpServletRequest.getSession().getAttribute(DcemConstants.URL_TENANT_PARAMETER);
			if (tenantEntity == null) {
				tenantEntity = applicationBean.getTenantFromRequest(httpServletRequest);
				if (tenantEntity == null) {
					logger.error("!!! NO TENANT FOUND, we take now the master tenant. Please check the Cluster-Configuration 'Host Domain Name'");
					tenantEntity = TenantIdResolver.getMasterTenant();
				}
				httpServletRequest.getSession().setAttribute(DcemConstants.URL_TENANT_PARAMETER, tenantEntity);
			}
			setTenant(tenantEntity);
			if (path.endsWith(OPEN_SUFFIX) || allowedPaths.contains(path)) { // excemption
				chain.doFilter(request, response);
				return;
			}
			if (containsAuthenticationCode(httpServletRequest)) {
				String currentUri = httpServletRequest.getRequestURL().toString();
				String queryStr = httpServletRequest.getQueryString();
				String fullUrl = currentUri + (queryStr != null ? "?" + queryStr : "");
				DomainAzure domainAzure = domainLogic.getDomainAzure();
				try {
					DcemUser dcemUserAzure = domainAzure.processAuthenticationCodeRedirect(httpServletRequest, currentUri, fullUrl);
					DcemUser dcemUser = userLogic.getDistinctUser(dcemUserAzure.getLoginId());
					if (dcemUser == null) {
						userLogic.addOrUpdateUserWoAuditing(dcemUserAzure);
					} else {
						dcemUser.sync(dcemUserAzure.getDcemLdapAttributes());
					}
					logUserIn(dcemUser, httpServletRequest);
					redirect(httpServletRequest, response, webName + "/" + welcomePage, false);
				} catch (DcemException e) {
					redirect(httpServletRequest, response, redirectionPage, false);
				} catch (Throwable e) {
					logger.info("", e);
					setPreLoginMessage (e.toString());
					redirect(httpServletRequest, response, redirectionPage, false);
				}
				CookieHelper.removeStateNonceCookies(httpServletResponse);
			}
		} catch (InvalidCipherTextIOException exp) { // should happen as now we have a JSF exception handler
			logger.info("Could not decrypt downloaded file", exp.toString());
			// try to redirect
			redirect(httpServletRequest, response, "/userportal/welcome.xhtml?Error=" + exp.getMessage(), true);
			return;
		} catch (Exception exp) {
			logger.warn("Web Filter Failed", exp);
			httpServletResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, exp.getMessage());
			return;
		}
		logger.debug("Redirected to: " + redirectionPage);
		redirect(httpServletRequest, response, redirectionPage, false);
	}

	private void redirect(HttpServletRequest httpServletRequest, ServletResponse response, String redirectPage, boolean encrpytionError) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append(httpServletRequest.getContextPath());
		sb.append(redirectPage);
		logger.debug("Redirect to from " + path + " to " + redirectionPage.toString());
		// For Ajax Request
		if (isAjaxRequest(httpServletRequest)) {
			response.setContentType("text/xml");
			response.getWriter().append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
					.printf("<partial-response><redirect url=\"%s\"></redirect></partial-response>", sb.toString());
		} else {
			try {
				((HttpServletResponse) response).sendRedirect(sb.toString());
			} catch (Exception e) {
				if (encrpytionError) {
					httpServletRequest.getSession().setAttribute(DcemConstants.DOWNLOAD_CIPHER_EXCEPTION, true);
				}
				logger.info("Couldn't redirect to " + sb.toString(), e.toString());
			}
		}
	}

	// protected void checkIfViewAllowed(HttpServletRequest request) throws
	// Exception {
	// return;
	// }

	@Override
	public void destroy() {
	}

	protected boolean isAjaxRequest(HttpServletRequest httpServletRequest) {
		return FACES_AJAX_REQUEST.equals(httpServletRequest.getHeader(FACES_REQUEST));
	}

	/*
	 * session shouldn't be checked for some pages. For example: for timeout page..
	 * Since we're redirecting to timeout page from this filter, if we don't disable
	 * session control for it, filter will again redirect to it and this will be
	 * result with an infinite loop...
	 */
	private boolean isSessionControlRequiredForThisResource(HttpServletRequest httpServletRequest) {

		String requestPath = httpServletRequest.getServletPath();
		if (requestPath == null)
			return false;
		String sessionControlStr = (String) httpServletRequest.getAttribute("isSessionControlRequired");
		boolean isSessionControlRequired = (sessionControlStr == null || "true".equals(sessionControlStr)) ? true : false;

		return (requestPath.contains("expired") == false && isSessionControlRequired);

	}

	private boolean isSessionInvalid(HttpServletRequest httpServletRequest) {
		return (httpServletRequest.getRequestedSessionId() != null) && !httpServletRequest.isRequestedSessionIdValid();
	}

	boolean containsAuthenticationCode(HttpServletRequest httpRequest) {
		Map<String, String[]> httpParameters = httpRequest.getParameterMap();

		boolean isPostRequest = httpRequest.getMethod().equalsIgnoreCase("POST");
		boolean containsErrorData = httpParameters.containsKey("error");
		boolean containIdToken = httpParameters.containsKey("id_token");
		boolean containsCode = httpParameters.containsKey("code");
		return isPostRequest && containsErrorData || containsCode || containIdToken;
	}

	public void setPreLoginMessage(String text) {
		// dummy to be overridden
	}

	// private Map<String, String> getHeadersInfo(HttpServletRequest request) {
	// Map<String, String> map = new HashMap<String, String>();
	// Enumeration<String> headerNames = request.getHeaderNames();
	// while (headerNames.hasMoreElements()) {
	// String key = (String) headerNames.nextElement();
	// String value = request.getHeader(key);
	// map.put(key, value);
	// // System.out.println("DcemFilter.getHeadersInfo() HTTP Header=" + key + "
	// Value=" + value );
	// }
	//
	// return map;
	// }
}
