package com.getddo.api.common.context;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.getddo.api.common.exception.CommonErrorCode;
import com.getddo.core.common.exception.BusinessException;
import com.getddo.core.user.domain.Membership;
import com.getddo.core.user.domain.User;
import com.getddo.core.user.domain.UserRole;
import com.getddo.core.user.service.UserService;

/**
 * {@code @CurrentUser User} 파라미터에 시연용 헤더를 DB와 대조한 사용자를 전달한다.
 *
 * <p>사용자 ID·역할·멤버십 헤더의 형식을 확인한 뒤 기존 UserService로 조회한다.
 * 헤더는 DB 값과 일치하는지 확인하는 입력이며, 반환하는 User의 정보는 DB 값을 사용한다.</p>
 *
 * <p>이 파라미터를 선언한 API에서만 실행하며 로그인이나 호출자의 신원을 인증하지 않는다.
 * INACTIVE 상태의 허용 여부와 업무별 권한·자격은 해당 기능의 Service가 판단한다.</p>
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

	// 다른 요청 속성과 이름이 겹치지 않도록 클래스 전체 이름을 사용한다.
	private static final String CURRENT_USER_ATTRIBUTE = CurrentUserArgumentResolver.class.getName() + ".user";
	private final UserService userService;

	public CurrentUserArgumentResolver(UserService userService) {
		this.userService = userService;
	}

	/** 어노테이션과 User 타입을 모두 확인해 다른 Controller 파라미터의 해석에 개입하지 않는다. */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType() == User.class;
	}

	/**
	 * 헤더 형식 → DB 조회 → 역할·멤버십 대조 순서로 확인하고, 검증된 User만 요청에 보관한다.
	 * UserService의 미등록 사용자 예외와 DB 장애는 기존 공통 예외 처리기로 전달한다.
	 */
	@Override
	public User resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
			NativeWebRequest request, WebDataBinderFactory binderFactory) {
		boolean membershipRequired = parameter.getParameterAnnotation(CurrentUser.class).membershipRequired();
		ContextHeaders headers = readHeaders(parameter, request);
		UUID id = headers.id;
		UserRole role = headers.role;
		Membership membership = headers.membership;
		// DB 조회는 요청당 한 번 재사용하되 파라미터별 멤버십 조건은 매번 확인한다.
		User user = (User) request.getAttribute(CURRENT_USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (user == null) {
			user = userService.findById(id);
		}
		// 헤더 역할로 권한을 부여하지 않고 저장된 역할과 일치하는지 확인한다.
		if (user.getRole() != role) {
			throw new BusinessException(CommonErrorCode.USER_ROLE_MISMATCH);
		}
		if (membershipRequired && user.getRole() == UserRole.USER) {
			// DB 멤버십이 없는 USER를 헤더 값으로 보충하지 않는다.
			if (user.getMembership() == null) {
				throw new BusinessException(CommonErrorCode.USER_MEMBERSHIP_REQUIRED);
			}
			if (user.getMembership() != membership) {
				throw new BusinessException(CommonErrorCode.USER_MEMBERSHIP_MISMATCH);
			}
		} else if (!membershipRequired && membership != null && user.getMembership() != membership) {
			throw new BusinessException(CommonErrorCode.USER_MEMBERSHIP_MISMATCH);
		}
		// 모든 대조가 끝난 뒤 보관한다. ADMIN의 반환 멤버십도 헤더가 아닌 DB 값을 유지한다.
		request.setAttribute(CURRENT_USER_ATTRIBUTE, user, RequestAttributes.SCOPE_REQUEST);
		return user;
	}

	/** 응모의 읽기 전용 ACTIVE 조회 전에 기존과 같은 헤더 형식만 검증한다. DB를 조회하지 않는다. */
	public UUID validateHeaders(MethodParameter parameter, NativeWebRequest request) {
		return readHeaders(parameter, request).id;
	}

	private ContextHeaders readHeaders(MethodParameter parameter, NativeWebRequest request) {
		boolean required = parameter.getParameterAnnotation(CurrentUser.class).membershipRequired();
		UUID id = parseId(header(request, "X-User-ID", true));
		UserRole role = parseRole(header(request, "X-User-Role", true));
		Membership membership = parseMembership(header(request, "X-User-Membership", required && role == UserRole.USER));
		return new ContextHeaders(id, role, membership);
	}

	private static final class ContextHeaders {
		private final UUID id;
		private final UserRole role;
		private final Membership membership;

		private ContextHeaders(UUID id, UserRole role, Membership membership) {
			this.id = id;
			this.role = role;
			this.membership = membership;
		}
	}

	/**
	 * 필수 헤더 누락은 401, 빈 값·중복 헤더는 400으로 구분한다.
	 * 선택 헤더를 생략한 경우에만 null을 반환하며, 전달된 값은 임의로 보정하지 않는다.
	 */
	private String header(NativeWebRequest request, String name, boolean required) {
		String[] values = request.getHeaderValues(name);
		if (values == null || values.length == 0) {
			if (required) {
				throw new BusinessException(CommonErrorCode.USER_CONTEXT_REQUIRED);
			}
			return null;
		}
		if (values.length != 1 || values[0].isBlank()) {
			throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		}
		return values[0];
	}

	/** UUID.fromString이 허용하는 축약 표현까지 통과하지 않도록 표준 UUID 문자열과 다시 대조한다. */
	private UUID parseId(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) {
				throw new IllegalArgumentException();
			}
			return id;
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		}
	}

	/** 계약에 정의한 대문자 역할(USER·ADMIN)만 허용한다. */
	private UserRole parseRole(String value) {
		try {
			return UserRole.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		}
	}

	/** 소문자 헤더 값을 도메인 enum으로 변환한다. 선택 헤더 생략은 null로 유지한다. */
	private Membership parseMembership(String value) {
		if (value == null) {
			return null;
		}
		return switch (value) {
			case "excellent" -> Membership.EXCELLENT;
			case "vip" -> Membership.VIP;
			case "vvip" -> Membership.VVIP;
			default -> throw new BusinessException(CommonErrorCode.INVALID_FORMAT);
		};
	}
}
