package com.example.gradox2.utils.mail;

/** Fallo de entrega del proveedor de correo: el mensaje no se aceptó. */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public MailDeliveryException(String message) {
        super(message);
    }
}
