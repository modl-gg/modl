package gg.modl.backend.staff.service;

import gg.modl.backend.auth.settings.SignInMethod;

public record InvitationPreview(String email, SignInMethod method) {
}
