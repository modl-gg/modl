package gg.modl.backend.infrastructure.validation;

import gg.modl.backend.infrastructure.util.IpAddresses;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class IpAddressValidator implements ConstraintValidator<ValidIpAddress, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return IpAddresses.isLiteral(value.trim());
    }
}
