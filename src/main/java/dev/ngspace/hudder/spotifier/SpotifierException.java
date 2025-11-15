package dev.ngspace.hudder.spotifier;

public class SpotifierException extends RuntimeException {
	public SpotifierException(String string) {
		super(string);
	}
	public SpotifierException(Exception e) {
		super(e);
	}
	public SpotifierException(Exception e, String string) {
		super(string, e);
	}

	private static final long serialVersionUID = -3629257556866415287L;
}
